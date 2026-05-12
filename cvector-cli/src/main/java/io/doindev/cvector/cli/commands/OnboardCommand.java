package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "onboard", description = "One-command codebase briefing from the active project's graph.")
public class OnboardCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public OnboardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            String pid = active.projectId();
            renderProjectHeader(active, cfg);
            renderGraphSize(q, pid);
            renderLanguages(q, pid);
            renderTopClasses(q, pid);
            renderRestEndpoints(q, pid);
            renderTables(q, pid);
            renderConfigKeys(q, pid);
            renderEnvVars(q, pid);
            renderDependencies(q, pid);
            renderCallHubs(q, pid);
        }
        return 0;
    }

    private static void renderProjectHeader(CvectorConfig.ProjectEntry active, CvectorConfig cfg) {
        section("PROJECT");
        System.out.println("  name:  " + active.name());
        System.out.println("  root:  " + active.rootPath());
        System.out.println("  id:    " + active.projectId());
        System.out.println("  neo4j: " + cfg.neo4j().uri());
    }

    private static void renderGraphSize(GraphQueries q, String pid) {
        section("GRAPH SIZE");
        Map<String, Long> nodes = q.nodeCounts(pid);
        Map<String, Long> edges = q.edgeCounts(pid);
        long files = nodes.getOrDefault("File", 0L);
        long methods = nodes.getOrDefault("Method", 0L);
        long classes = nodes.getOrDefault("Class", 0L);
        long edgeTotal = edges.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("  files: %d | classes: %d | methods: %d%n", files, classes, methods);
        System.out.printf("  edges: %d total across %d types%n", edgeTotal, edges.size());
    }

    private static void renderLanguages(GraphQueries q, String pid) {
        section("LANGUAGES");
        print(q.raw(
                "MATCH (f:File {projectId: $pid}) RETURN f.language AS language, count(*) AS files ORDER BY files DESC",
                Map.of("pid", pid)));
    }

    private static void renderTopClasses(GraphQueries q, String pid) {
        section("TOP 10 CLASSES (by method count)");
        print(q.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "RETURN c.fqName AS fqName, count(m) AS methods ORDER BY methods DESC LIMIT 10",
                Map.of("pid", pid)));
    }

    private static void renderRestEndpoints(GraphQueries q, String pid) {
        section("REST ENDPOINTS");
        print(q.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "RETURN e.httpMethod AS method, e.path AS path ORDER BY path",
                Map.of("pid", pid)));
    }

    private static void renderTables(GraphQueries q, String pid) {
        section("DATABASE TABLES");
        print(q.raw(
                "MATCH (t:Table {projectId: $pid}) OPTIONAL MATCH (t)-[:CONTAINS]->(c:Column) "
                        + "RETURN t.name AS table, count(c) AS columns ORDER BY t.name",
                Map.of("pid", pid)));
    }

    private static void renderConfigKeys(GraphQueries q, String pid) {
        section("CONFIG KEYS (top 20)");
        print(q.raw(
                "MATCH (k:ConfigKey {projectId: $pid}) RETURN k.fqName AS key, k.value AS value ORDER BY k.fqName LIMIT 20",
                Map.of("pid", pid)));
    }

    private static void renderEnvVars(GraphQueries q, String pid) {
        section("ENV VARS");
        print(q.raw(
                "MATCH (e:EnvVar {projectId: $pid}) RETURN e.name AS name, e.value AS value ORDER BY e.name",
                Map.of("pid", pid)));
    }

    private static void renderDependencies(GraphQueries q, String pid) {
        section("MAVEN DEPENDENCIES");
        print(q.raw(
                "MATCH (d:MavenDependency {projectId: $pid}) "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, d.version AS version, d.scope AS scope "
                        + "ORDER BY groupId, artifactId",
                Map.of("pid", pid)));
    }

    private static void renderCallHubs(GraphQueries q, String pid) {
        section("HIGHEST-DEGREE METHODS (call graph hubs)");
        print(q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "OPTIONAL MATCH (m)-[out:CALLS]->() "
                        + "OPTIONAL MATCH ()-[in:CALLS]->(m) "
                        + "WITH m, count(DISTINCT out) AS outDeg, count(DISTINCT in) AS inDeg "
                        + "WHERE outDeg + inDeg > 0 "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, outDeg + inDeg AS total "
                        + "ORDER BY total DESC LIMIT 10",
                Map.of("pid", pid)));
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void print(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        TableRenderer.render(System.out, rows);
    }
}
