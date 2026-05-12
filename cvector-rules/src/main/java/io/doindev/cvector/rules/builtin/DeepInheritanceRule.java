package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepInheritanceRule implements Rule {

    public static final String NAME = "deep-inheritance";

    private static final String NEO4J_CYPHER =
            "MATCH path = (c:Class {projectId: $pid})-[:EXTENDS*]->(ancestor:Class) "
                    + "WITH c, max(length(path)) AS depth "
                    + "WHERE depth >= $t "
                    + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, depth "
                    + "ORDER BY depth DESC";

    /**
     * Kuzu equivalent: variable-length traversal works (probed), but we cap it to keep the query
     * bounded. Real-world hierarchies past ~16 levels are pathological enough that we'd want a
     * separate rule for them anyway.
     */
    private static final String KUZU_CYPHER =
            "MATCH path = (c:Node)-[:EXTENDS*1..16]->(ancestor:Node) "
                    + "WHERE c.label = 'Class' AND ancestor.label = 'Class' "
                    + "WITH c, max(length(path)) AS depth "
                    + "WHERE depth >= $t "
                    + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, depth "
                    + "ORDER BY depth DESC";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Class extends-chain exceeds the deepInheritance threshold; reconsider the hierarchy.";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        int threshold = cfg.intThreshold("deepInheritance", 5);
        String cypher = "kuzu".equals(store.backend()) ? KUZU_CYPHER : NEO4J_CYPHER;
        var rows = store.rawCypher(cypher, Map.of("pid", projectId, "t", (long) threshold)).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long depth = ((Number) r.get("depth")).longValue();
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.WARN,
                    String.valueOf(r.get("fqName")),
                    "extends chain depth " + depth + " (threshold " + threshold + ")",
                    r.get("fileId") == null ? null : String.valueOf(r.get("fileId")),
                    line
            ));
        }
        return out;
    }
}
