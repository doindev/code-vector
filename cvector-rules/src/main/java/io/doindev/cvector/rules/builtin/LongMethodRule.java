package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LongMethodRule implements Rule {

    public static final String NAME = "long-method";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Method body exceeds the longMethodLines threshold.";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphQueries q, RulesConfig cfg) {
        int threshold = cfg.intThreshold("longMethodLines", 80);
        var rows = q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                        + "AND (m.endLine - m.startLine) >= $t "
                        + "OPTIONAL MATCH (f:File {id: m.fileId}) "
                        + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line, "
                        + "(m.endLine - m.startLine) AS lines, f.path AS path "
                        + "ORDER BY lines DESC",
                Map.of("pid", projectId, "t", threshold)
        );
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String path = r.get("path") == null ? null : String.valueOf(r.get("path"));
            if (cfg.isPathExcluded(path)) continue;
            long lines = ((Number) r.get("lines")).longValue();
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.WARN,
                    String.valueOf(r.get("fqName")),
                    "method spans " + lines + " lines (threshold " + threshold + ")",
                    r.get("fileId") == null ? null : String.valueOf(r.get("fileId")),
                    line
            ));
        }
        return out;
    }
}
