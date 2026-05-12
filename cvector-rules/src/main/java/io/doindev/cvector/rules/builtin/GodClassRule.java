package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GodClassRule implements Rule {

    public static final String NAME = "god-class";

    private static final String NEO4J_CYPHER =
            "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                    + "OPTIONAL MATCH (f:File)-[:CONTAINS]->(c) "
                    + "WITH c, f, count(m) AS methods "
                    + "WHERE methods >= $t "
                    + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, methods, f.path AS path "
                    + "ORDER BY methods DESC";

    /**
     * Kuzu doesn't preserve the source-MATCH variable across OPTIONAL MATCH + WITH in the same
     * way Neo4j does (we hit this in onboardSummary too). So we run the count first, then look
     * up the file path per-class in Java when we materialise violations.
     */
    private static final String KUZU_CYPHER =
            "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                    + "WHERE c.label = 'Class' AND m.label = 'Method' "
                    + "WITH c, count(m) AS methods "
                    + "WHERE methods >= $t "
                    + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, methods "
                    + "ORDER BY methods DESC";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Class contains more methods than the godClassMethods threshold; split responsibilities.";
    }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        int threshold = cfg.intThreshold("godClassMethods", 20);
        String cypher = "kuzu".equals(store.backend()) ? KUZU_CYPHER : NEO4J_CYPHER;
        var rows = store.rawCypher(cypher, Map.of("pid", projectId, "t", (long) threshold)).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String fileId = r.get("fileId") == null ? null : String.valueOf(r.get("fileId"));
            String path = r.get("path") == null && fileId != null
                    ? pathFor(store, projectId, fileId)
                    : (r.get("path") == null ? null : String.valueOf(r.get("path")));
            if (cfg.isPathExcluded(path)) continue;
            long methods = ((Number) r.get("methods")).longValue();
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.ERROR,
                    String.valueOf(r.get("fqName")),
                    "class has " + methods + " methods (threshold " + threshold + ")",
                    fileId,
                    line
            ));
        }
        return out;
    }

    private static String pathFor(GraphStore store, String projectId, String fileId) {
        Map<String, Object> file = store.fileOf(projectId, fileId);
        Object p = file.get("path");
        return p == null ? null : p.toString();
    }
}
