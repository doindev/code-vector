package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepInheritanceRule implements Rule {

    public static final String NAME = "deep-inheritance";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Class extends-chain exceeds the deepInheritance threshold; reconsider the hierarchy.";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphQueries q, RulesConfig cfg) {
        int threshold = cfg.intThreshold("deepInheritance", 5);
        var rows = q.raw(
                "MATCH path = (c:Class {projectId: $pid})-[:EXTENDS*]->(ancestor:Class) "
                        + "WITH c, max(length(path)) AS depth "
                        + "WHERE depth >= $t "
                        + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, depth "
                        + "ORDER BY depth DESC",
                Map.of("pid", projectId, "t", threshold)
        );
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
