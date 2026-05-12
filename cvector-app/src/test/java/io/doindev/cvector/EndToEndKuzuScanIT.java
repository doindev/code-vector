package io.doindev.cvector;

import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuBulkLoader;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuPostScan;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.parser.config.EnvParserAdapter;
import io.doindev.cvector.parser.config.JsonParserAdapter;
import io.doindev.cvector.parser.config.PomParserAdapter;
import io.doindev.cvector.parser.config.YamlParserAdapter;
import io.doindev.cvector.parser.java.JavaParserAdapter;
import io.doindev.cvector.parser.python.PythonParserAdapter;
import io.doindev.cvector.parser.sql.SqlParserAdapter;
import io.doindev.cvector.parser.style.StylesheetParserAdapter;
import io.doindev.cvector.parser.ts.TypeScriptParserAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercises of the embedded KuzuDB pipeline. Mirrors {@link EndToEndScanIT} but
 * routes through {@link EmbeddedKuzu} + {@link KuzuBulkLoader} + {@link KuzuGraphStore} instead
 * of a Testcontainers-spawned Neo4j. Each test owns its own temp Kuzu directory so they run in
 * parallel without contention and require zero external infrastructure.
 */
class EndToEndKuzuScanIT {

    @Test
    void bulkScanProducesFullGraph(@TempDir Path tmp) throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/sample-project").toAbsolutePath().normalize();
        assertThat(fixture).exists().isDirectory();

        try (EmbeddedKuzu kuzu = new EmbeddedKuzu(tmp.resolve("graph.kuzu"))) {
            new KuzuSchemaBootstrap(kuzu).bootstrap();
            String pid = scanFixture(kuzu, fixture, "sample-project");

            KuzuPostScan.resolveDeferredHandlers(kuzu, pid);
            KuzuPostScan.resolveUnresolvedCalls(kuzu, pid);

            KuzuGraphStore store = new KuzuGraphStore(kuzu);
            Map<String, Long> nodes = store.nodeCounts(pid);
            assertThat(nodes).containsKeys("File", "Class", "Method", "Table", "Column",
                    "ApiEndpoint", "ConfigKey", "MavenDependency");
            assertThat(nodes.get("Table")).isGreaterThanOrEqualTo(2L);
            assertThat(nodes.get("Column")).isGreaterThanOrEqualTo(7L);

            Map<String, Long> edges = store.edgeCounts(pid);
            assertThat(edges).containsKeys("CONTAINS", "CALLS", "EXPOSES", "HANDLES");

            List<Map<String, Object>> greetMatches = store.findSymbol(pid, "greet");
            assertThat(greetMatches).as("findSymbol('greet')").isNotEmpty();
            List<Map<String, Object>> greetCallers = store.callers(pid,
                    String.valueOf(greetMatches.get(0).get("id")));
            assertThat(greetCallers)
                    .anyMatch(r -> String.valueOf(r.get("fqName")).contains("shout"))
                    .anyMatch(r -> String.valueOf(r.get("fqName")).contains("main"));

            Map<String, List<Map<String, Object>>> infra = store.infrastructureSummary(pid);
            List<Map<String, Object>> endpoints = infra.get("apiEndpoints");
            assertThat(endpoints)
                    .anyMatch(r -> "GET".equals(r.get("httpMethod"))
                            && String.valueOf(r.get("path")).startsWith("/users"))
                    .anyMatch(r -> "POST".equals(r.get("httpMethod"))
                            && "/users".equals(r.get("path")));

            List<Map<String, Object>> configKeys = infra.get("configKeys");
            assertThat(configKeys).extracting(r -> r.get("fqName"))
                    .contains("spring.datasource.url", "server.port");

            List<Map<String, Object>> deps = store.mavenDependencies(pid);
            assertThat(deps)
                    .anyMatch(r -> "org.springframework.boot".equals(r.get("groupId"))
                            && "spring-boot-starter-web".equals(r.get("artifactId")))
                    .anyMatch(r -> "org.postgresql".equals(r.get("groupId"))
                            && "postgresql".equals(r.get("artifactId")));
        }
    }

    @Test
    void commentsSuppressedAcrossAllParsers(@TempDir Path tmp) throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/comment-audit").toAbsolutePath().normalize();
        assertThat(fixture).exists().isDirectory();

        try (EmbeddedKuzu kuzu = new EmbeddedKuzu(tmp.resolve("graph.kuzu"))) {
            new KuzuSchemaBootstrap(kuzu).bootstrap();
            String pid = scanFixture(kuzu, fixture, "comment-audit");

            // Comment-suppression: no node names/fqNames should leak placeholders from comments.
            // Run individual SearchByName probes for each leak token since rawCypher's regex isn't
            // Kuzu-compatible without backend-specific phrasing.
            KuzuGraphStore store = new KuzuGraphStore(kuzu);
            for (String token : List.of("commented", "fake", "should", "lookslike", "looks_like", "nested_in")) {
                List<Map<String, Object>> hits = store.searchByName(pid, token, null, 50);
                assertThat(hits)
                        .as("no leak for token '%s' (commented declarations bled into the graph)", token)
                        .isEmpty();
            }

            Map<String, Long> nodes = store.nodeCounts(pid);
            assertThat(nodes.get("Class")).as("real classes: Real, RealTsClass, RealPyClass").isGreaterThanOrEqualTo(3L);
            assertThat(nodes.get("Method")).as("real methods across all parsers").isGreaterThanOrEqualTo(5L);
            assertThat(nodes.get("Table")).as("real SQL table").isEqualTo(1L);
            assertThat(nodes.get("MavenDependency")).as("real maven dependency").isEqualTo(1L);
            assertThat(nodes.get("ApiEndpoint")).as("real Express route").isEqualTo(1L);
            assertThat(nodes.get("CssClass")).as("real CSS + SCSS classes").isGreaterThanOrEqualTo(2L);
            assertThat(nodes.get("DesignToken")).as("--real-token + $real-scss-var").isGreaterThanOrEqualTo(2L);
            assertThat(nodes.getOrDefault("ConfigKey", 0L))
                    .as("real.key (YAML) + real.property + real_json_key").isGreaterThanOrEqualTo(3L);
            assertThat(nodes.getOrDefault("EnvVar", 0L)).as("REAL_ENV_VAR").isGreaterThanOrEqualTo(1L);
        }
    }

    private static String scanFixture(EmbeddedKuzu kuzu, Path fixture, String displayName) throws Exception {
        String pid = "test-" + displayName + "-" + UUID.randomUUID();
        ProjectContext ctx = new ProjectContext(pid, displayName, fixture);
        List<Parser> parsers = List.of(
                new JavaParserAdapter(),
                new SqlParserAdapter(),
                new PomParserAdapter(),
                new YamlParserAdapter(),
                new JsonParserAdapter(),
                new EnvParserAdapter(),
                new TypeScriptParserAdapter(),
                new StylesheetParserAdapter(),
                new PythonParserAdapter()
        );
        for (Parser p : parsers) p.prepare(ctx);
        try (GraphIngestor ingestor = new KuzuBulkLoader(kuzu)) {
            try (Stream<Path> walk = Files.walk(fixture)) {
                walk.filter(Files::isRegularFile).forEach(f -> {
                    for (Parser p : parsers) {
                        if (p.accepts(f)) { p.parse(f, ctx, ingestor); break; }
                    }
                });
            }
            for (Parser p : parsers) p.finish();
            ingestor.flush();
        }
        return pid;
    }
}
