package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GodFileRule implements Rule {

    public static final String NAME = "god-file";

    private static final String NEO4J_CYPHER =
            "MATCH (f:File {projectId: $pid})-[:CONTAINS]->(:Class)-[:CONTAINS]->(m:Method) "
                    + "WITH f, count(m) AS methods "
                    + "WHERE methods >= $t "
                    + "RETURN f.path AS path, f.id AS fileId, methods ORDER BY methods DESC";

    private static final String KUZU_CYPHER =
            "MATCH (f:Node)-[:CONTAINS]->(c:Node)-[:CONTAINS]->(m:Node) "
                    + "WHERE f.label = 'File' AND c.label = 'Class' AND m.label = 'Method' "
                    + "WITH f, count(m) AS methods "
                    + "WHERE methods >= $t "
                    + "RETURN f.path AS path, f.id AS fileId, methods ORDER BY methods DESC";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "File contains more methods than the godFileMethods threshold; consider splitting it.";
    }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        int threshold = cfg.intThreshold("godFileMethods", 30);
        String cypher = "kuzu".equals(store.backend()) ? KUZU_CYPHER : NEO4J_CYPHER;
        var rows = store.rawCypher(cypher, Map.of("pid", projectId, "t", (long) threshold)).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String path = String.valueOf(r.get("path"));
            if (cfg.isPathExcluded(path)) continue;
            long methods = ((Number) r.get("methods")).longValue();
            out.add(new Violation(
                    NAME, Severity.ERROR, path,
                    "file has " + methods + " methods (threshold " + threshold + ")",
                    String.valueOf(r.get("fileId")), null
            ));
        }
        return out;
    }
}
