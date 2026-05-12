package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "flows", description = "Trace execution flows from entry points through the call graph.")
public class FlowsCommand implements Callable<Integer> {

    @Option(names = "--max-depth", description = "Max BFS depth from each entry point (default 4).")
    private int maxDepth = 4;

    @Option(names = "--limit", description = "Max entry points to display (default 20).")
    private int limit = 20;

    @Option(names = "--kind", description = "Entry-point kind filter: rest, main, test, all (default all).")
    private String kind = "all";

    private final CvectorRuntime runtime;

    public FlowsCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        String pid = active.projectId();

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);

            if ("rest".equals(kind) || "all".equals(kind)) {
                section("REST endpoint flows");
                renderFlows(q, pid,
                        "MATCH (e:ApiEndpoint {projectId: $pid})-[:HANDLES]->(handler:Method) "
                                + "RETURN handler.id AS id, e.httpMethod + ' ' + e.path AS entry, handler.fqName AS handler "
                                + "ORDER BY entry LIMIT $lim");
            }

            if ("main".equals(kind) || "all".equals(kind)) {
                section("Main-method flows");
                renderFlows(q, pid,
                        "MATCH (m:Method {projectId: $pid, name: 'main'}) "
                                + "WHERE coalesce(m.isStatic, false) = true "
                                + "RETURN m.id AS id, m.fqName AS entry, m.fqName AS handler "
                                + "ORDER BY m.fqName LIMIT $lim");
            }

            if ("test".equals(kind) || "all".equals(kind)) {
                section("Test-method flows");
                renderFlows(q, pid,
                        "MATCH (m:Method {projectId: $pid}) "
                                + "WHERE coalesce(m.isTest, false) = true "
                                + "RETURN m.id AS id, m.fqName AS entry, m.fqName AS handler "
                                + "ORDER BY m.fqName LIMIT $lim");
            }
        }
        return 0;
    }

    private void renderFlows(GraphQueries q, String pid, String cypher) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pid", pid);
        params.put("lim", limit);
        List<Map<String, Object>> entries = q.raw(cypher, params);
        if (entries.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        for (Map<String, Object> e : entries) {
            String entryLabel = String.valueOf(e.get("entry"));
            String handlerFq = String.valueOf(e.get("handler"));
            String id = String.valueOf(e.get("id"));

            String depthLiteral = String.valueOf(Math.max(1, maxDepth));
            List<Map<String, Object>> reached = q.raw(
                    "MATCH (start {id: $id, projectId: $pid}) "
                            + "MATCH (start)-[:CALLS*1.." + depthLiteral + "]->(callee:Method) "
                            + "WHERE callee.projectId = $pid "
                            + "RETURN DISTINCT callee.fqName AS fqName LIMIT 100",
                    Map.of("id", id, "pid", pid)
            );

            System.out.println();
            System.out.println("entry: " + entryLabel);
            System.out.println("  handler: " + handlerFq);
            if (reached.isEmpty()) {
                System.out.println("  reaches: (no downstream CALLS within depth " + maxDepth + ")");
            } else {
                System.out.println("  reaches " + reached.size() + " methods within depth " + maxDepth + ":");
                int shown = 0;
                for (Map<String, Object> r : reached) {
                    if (shown >= 8) {
                        System.out.println("    ... (" + (reached.size() - shown) + " more)");
                        break;
                    }
                    System.out.println("    - " + r.get("fqName"));
                    shown++;
                }
            }
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }
}
