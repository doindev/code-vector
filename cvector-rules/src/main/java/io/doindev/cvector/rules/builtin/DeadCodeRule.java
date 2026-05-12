package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeadCodeRule implements Rule {

    public static final String NAME = "dead-code";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Method has no incoming CALLS edges (and is not an entry point: not a test, not a controller handler, not main).";
    }

    @Override public Severity severity() { return Severity.WARN; }

    @Override
    public List<Violation> evaluate(String projectId, GraphQueries q, RulesConfig cfg) {
        var rows = q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE NOT EXISTS { MATCH ()-[:CALLS]->(m) } "
                        + "AND coalesce(m.isTest, false) = false "
                        + "AND NOT EXISTS { MATCH (:ApiEndpoint)-[:HANDLES]->(m) } "
                        + "AND m.name <> 'main' "
                        + "AND coalesce(m.isConstructor, false) = false "
                        + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                        + "OPTIONAL MATCH (f:File {id: m.fileId}) "
                        + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line, f.path AS path "
                        + "ORDER BY m.fqName LIMIT 500",
                Map.of("pid", projectId)
        );
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String path = r.get("path") == null ? null : String.valueOf(r.get("path"));
            if (cfg.isPathExcluded(path)) continue;
            Integer line = r.get("line") == null ? null : ((Number) r.get("line")).intValue();
            out.add(new Violation(
                    NAME, Severity.WARN,
                    String.valueOf(r.get("fqName")),
                    "no incoming CALLS edges",
                    r.get("fileId") == null ? null : String.valueOf(r.get("fileId")),
                    line
            ));
        }
        return out;
    }
}
