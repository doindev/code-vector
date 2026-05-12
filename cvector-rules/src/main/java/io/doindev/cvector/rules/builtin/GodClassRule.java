package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GodClassRule implements Rule {

    public static final String NAME = "god-class";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Class contains more methods than the godClassMethods threshold; split responsibilities.";
    }

    @Override
    public List<Violation> evaluate(String projectId, GraphQueries q, RulesConfig cfg) {
        int threshold = cfg.intThreshold("godClassMethods", 20);
        var rows = q.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "OPTIONAL MATCH (f:File)-[:CONTAINS]->(c) "
                        + "WITH c, f, count(m) AS methods "
                        + "WHERE methods >= $t "
                        + "RETURN c.fqName AS fqName, c.fileId AS fileId, c.startLine AS line, methods, f.path AS path "
                        + "ORDER BY methods DESC",
                Map.of("pid", projectId, "t", threshold)
        );
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String path = r.get("path") == null ? null : String.valueOf(r.get("path"));
            if (cfg.isPathExcluded(path)) continue;
            long methods = ((Number) r.get("methods")).longValue();
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.ERROR,
                    String.valueOf(r.get("fqName")),
                    "class has " + methods + " methods (threshold " + threshold + ")",
                    r.get("fileId") == null ? null : String.valueOf(r.get("fileId")),
                    line
            ));
        }
        return out;
    }
}
