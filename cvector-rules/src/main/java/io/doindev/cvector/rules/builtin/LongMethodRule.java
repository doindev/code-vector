package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LongMethodRule implements Rule {

    public static final String NAME = "long-method";

    private static final String NEO4J_CYPHER =
            "MATCH (m:Method {projectId: $pid}) "
                    + "WHERE m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                    + "AND (m.endLine - m.startLine) >= $t "
                    + "OPTIONAL MATCH (f:File {id: m.fileId}) "
                    + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line, "
                    + "(m.endLine - m.startLine) AS lines, f.path AS path "
                    + "ORDER BY lines DESC";

    /**
     * Kuzu's OPTIONAL MATCH binding rules don't let us join (f:File {id: m.fileId}) in one query
     * without re-binding m through a WITH. Cheaper to do without the path lookup and resolve in
     * Java via {@link GraphStore#fileOf}.
     */
    private static final String KUZU_CYPHER =
            "MATCH (m:Node) WHERE m.label = 'Method' "
                    + "AND m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                    + "AND (m.endLine - m.startLine) >= $t "
                    + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line, "
                    + "(m.endLine - m.startLine) AS lines "
                    + "ORDER BY lines DESC";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Method body exceeds the longMethodLines threshold.";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        int threshold = cfg.intThreshold("longMethodLines", 80);
        String cypher = "kuzu".equals(store.backend()) ? KUZU_CYPHER : NEO4J_CYPHER;
        var rows = store.rawCypher(cypher, Map.of("pid", projectId, "t", (long) threshold)).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String fileId = r.get("fileId") == null ? null : String.valueOf(r.get("fileId"));
            String path = r.get("path") == null && fileId != null
                    ? pathFor(store, projectId, fileId)
                    : (r.get("path") == null ? null : String.valueOf(r.get("path")));
            if (cfg.isPathExcluded(path)) continue;
            long lines = ((Number) r.get("lines")).longValue();
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.WARN,
                    String.valueOf(r.get("fqName")),
                    "method spans " + lines + " lines (threshold " + threshold + ")",
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
