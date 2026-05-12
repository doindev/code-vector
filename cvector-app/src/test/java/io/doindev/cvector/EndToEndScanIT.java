package io.doindev.cvector;

import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.parser.config.EnvParserAdapter;
import io.doindev.cvector.parser.config.JsonParserAdapter;
import io.doindev.cvector.parser.config.PomParserAdapter;
import io.doindev.cvector.parser.config.YamlParserAdapter;
import io.doindev.cvector.parser.java.JavaParserAdapter;
import io.doindev.cvector.parser.python.PythonParserAdapter;
import io.doindev.cvector.parser.sql.SqlParserAdapter;
import io.doindev.cvector.parser.style.StylesheetParserAdapter;
import io.doindev.cvector.parser.ts.TypeScriptParserAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.Neo4jContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndScanIT {

    private static Neo4jContainer<?> container;
    private static Neo4jClient client;
    private static GraphQueries queries;
    private static final List<String> projectIds = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        String externalBolt = System.getenv("NEO4J_TEST_BOLT");
        String externalUser = System.getenv().getOrDefault("NEO4J_TEST_USER", "neo4j");
        String externalPass = System.getenv().getOrDefault("NEO4J_TEST_PASSWORD", "neo4j");

        if (externalBolt != null && !externalBolt.isBlank()) {
            client = new Neo4jClient(externalBolt, externalUser, externalPass);
            if (!client.ping()) {
                client.close();
                client = null;
                throw new TestAbortedException("NEO4J_TEST_BOLT set but unreachable at " + externalBolt);
            }
        } else {
            try {
                container = new Neo4jContainer<>("neo4j:5").withoutAuthentication();
                container.start();
                client = new Neo4jClient(container.getBoltUrl(), "neo4j", "neo4j");
            } catch (RuntimeException e) {
                throw new TestAbortedException(
                        "No Docker reachable for Testcontainers and NEO4J_TEST_BOLT not set; "
                                + "set NEO4J_TEST_BOLT=bolt://host:port to run this IT against an existing Neo4j 5. "
                                + "Underlying error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        new SchemaBootstrap(client).bootstrap();
        queries = new GraphQueries(client);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            for (String pid : projectIds) {
                try {
                    client.write("MATCH (n) WHERE n.projectId = $pid DETACH DELETE n", Map.of("pid", pid));
                } catch (RuntimeException ignored) {
                }
            }
            client.close();
        }
        if (container != null) container.stop();
    }

    @Test
    void scansFixtureAndBuildsFullGraph() throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/sample-project").toAbsolutePath().normalize();
        assertThat(fixture).exists().isDirectory();

        String pid = scanFixture(fixture, "sample-project");

        Map<String, Long> nodes = queries.nodeCounts(pid);
        assertThat(nodes).containsKeys("File", "Class", "Method", "Table", "Column", "ApiEndpoint", "ConfigKey", "MavenDependency");
        assertThat(nodes.get("Table")).isGreaterThanOrEqualTo(2L);
        assertThat(nodes.get("Column")).isGreaterThanOrEqualTo(7L);

        Map<String, Long> edges = queries.edgeCounts(pid);
        assertThat(edges).containsKeys("CONTAINS", "CALLS", "EXPOSES", "HANDLES");

        List<Map<String, Object>> greetCallers = queries.callers(pid,
                queries.findSymbol(pid, "greet").get(0).get("id").toString());
        assertThat(greetCallers)
                .anyMatch(r -> String.valueOf(r.get("fqName")).contains("shout"))
                .anyMatch(r -> String.valueOf(r.get("fqName")).contains("main"));

        List<Map<String, Object>> endpoints = queries.raw(
                "MATCH (e:ApiEndpoint) WHERE e.projectId = $pid RETURN e.httpMethod AS httpMethod, e.path AS path ORDER BY path",
                Map.of("pid", pid)
        );
        assertThat(endpoints)
                .anyMatch(r -> "GET".equals(r.get("httpMethod")) && String.valueOf(r.get("path")).startsWith("/users"))
                .anyMatch(r -> "POST".equals(r.get("httpMethod")) && "/users".equals(r.get("path")));

        List<Map<String, Object>> configKeys = queries.raw(
                "MATCH (c:ConfigKey) WHERE c.projectId = $pid RETURN c.fqName AS fqName",
                Map.of("pid", pid)
        );
        assertThat(configKeys).extracting(r -> r.get("fqName")).contains("spring.datasource.url", "server.port");

        List<Map<String, Object>> deps = queries.raw(
                "MATCH (d:MavenDependency) WHERE d.projectId = $pid RETURN d.fqName AS fqName",
                Map.of("pid", pid)
        );
        assertThat(deps).extracting(r -> r.get("fqName"))
                .contains("org.springframework.boot:spring-boot-starter-web", "org.postgresql:postgresql");
    }

    @Test
    void commentsSuppressedAcrossAllParsers() throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/comment-audit").toAbsolutePath().normalize();
        assertThat(fixture).exists().isDirectory();

        String pid = scanFixture(fixture, "comment-audit");

        List<Map<String, Object>> leaks = queries.raw(
                "MATCH (n) WHERE n.projectId = $pid "
                        + "AND (toLower(n.name) CONTAINS 'commented' OR toLower(n.fqName) CONTAINS 'commented' "
                        + "  OR toLower(n.name) CONTAINS 'fake' OR toLower(n.fqName) CONTAINS 'fake' "
                        + "  OR toLower(n.name) CONTAINS 'should' OR toLower(n.fqName) CONTAINS 'should' "
                        + "  OR toLower(n.name) CONTAINS 'lookslike' OR toLower(n.fqName) CONTAINS 'lookslike' "
                        + "  OR toLower(n.name) CONTAINS 'looks_like' OR toLower(n.fqName) CONTAINS 'looks_like' "
                        + "  OR toLower(n.name) CONTAINS 'nested_in' OR toLower(n.fqName) CONTAINS 'nested_in') "
                        + "RETURN labels(n)[0] AS label, n.fqName AS fqName ORDER BY fqName",
                Map.of("pid", pid)
        );
        assertThat(leaks)
                .as("no commented-out declarations should appear in the graph; leaks: %s", leaks)
                .isEmpty();

        Map<String, Long> nodes = queries.nodeCounts(pid);
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

    private String scanFixture(Path fixture, String displayName) throws Exception {
        String pid = "test-" + displayName + "-" + UUID.randomUUID();
        projectIds.add(pid);
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
        try (Ingestor ingestor = new Ingestor(client)) {
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
