package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeadCodeRule implements Rule {

    public static final String NAME = "dead-code";

    private static final String NEO4J_CYPHER =
            "MATCH (m:Method {projectId: $pid}) "
                    + "WHERE NOT EXISTS { MATCH ()-[:CALLS]->(m) } "
                    + "AND coalesce(m.isTest, false) = false "
                    + "AND NOT EXISTS { MATCH (:ApiEndpoint)-[:HANDLES]->(m) } "
                    + "AND m.name <> 'main' "
                    + "AND coalesce(m.isConstructor, false) = false "
                    + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                    + "OPTIONAL MATCH (f:File {id: m.fileId}) "
                    + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line, f.path AS path "
                    + "ORDER BY m.fqName LIMIT 500";

    /**
     * Kuzu equivalent: replace NOT EXISTS subqueries with OPTIONAL MATCH + count=0. Two passes
     * (CALLS check, HANDLES check) get combined via WITH chains. File path lookup deferred to Java.
     */
    private static final String KUZU_CYPHER =
            "MATCH (m:Node) WHERE m.label = 'Method' "
                    + "AND coalesce(m.isTest, false) = false "
                    + "AND m.name <> 'main' "
                    + "AND coalesce(m.isConstructor, false) = false "
                    + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                    + "OPTIONAL MATCH ()-[r:CALLS]->(m) "
                    + "WITH m, count(r) AS incomingCalls "
                    + "WHERE incomingCalls = 0 "
                    + "OPTIONAL MATCH (e:Node)-[:HANDLES]->(m) WHERE e.label = 'ApiEndpoint' "
                    + "WITH m, count(e) AS handlerEdges "
                    + "WHERE handlerEdges = 0 "
                    + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line "
                    + "ORDER BY m.fqName LIMIT 500";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Method has no incoming CALLS edges (and is not an entry point: not a test, not a controller handler, not main).";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        String cypher = "kuzu".equals(store.backend()) ? KUZU_CYPHER : NEO4J_CYPHER;
        var rows = store.rawCypher(cypher, Map.of("pid", projectId)).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String fileId = r.get("fileId") == null ? null : String.valueOf(r.get("fileId"));
            String path = r.get("path") == null && fileId != null
                    ? pathFor(store, projectId, fileId)
                    : (r.get("path") == null ? null : String.valueOf(r.get("path")));
            if (cfg.isPathExcluded(path)) continue;
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.WARN,
                    String.valueOf(r.get("fqName")),
                    "no incoming CALLS edges",
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
