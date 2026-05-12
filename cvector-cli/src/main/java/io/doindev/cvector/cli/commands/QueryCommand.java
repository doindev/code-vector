package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "query", description = "Execute a raw Cypher query against the active project.")
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Cypher query (use $pid for the active project id).")
    private String cypher;

    @Option(names = "--json", description = "Output JSON instead of a table.")
    private boolean json;

    private final CvectorRuntime runtime;

    public QueryCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            Map<String, Object> params = new HashMap<>();
            params.put("pid", active.projectId());
            GraphQueries.QueryResult result = q.rawAutoTyped(cypher, params);
            if (json) {
                System.out.println(toJsonArray(result.rows()));
            } else if (result.isWrite()) {
                System.out.println("(write executed)");
            } else if (result.rows().isEmpty()) {
                System.out.println("(no results)");
            } else {
                TableRenderer.render(System.out, result.rows());
            }
        }
        return 0;
    }

    private String toJsonArray(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJsonObject(rows.get(i)));
        }
        return sb.append("]").toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : map.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append('"').append(escape(e.getKey())).append('"').append(":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof Map<?, ?> m) sb.append(toJsonObject((Map<String, Object>) m));
            else if (v instanceof List<?> l) sb.append(toJsonList(l));
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonList(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Object v = list.get(i);
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof Map<?, ?> m) sb.append(toJsonObject((Map<String, Object>) m));
            else if (v instanceof List<?> l) sb.append(toJsonList(l));
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append("]").toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
