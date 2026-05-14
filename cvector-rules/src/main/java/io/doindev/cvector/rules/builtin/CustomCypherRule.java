package io.doindev.cvector.rules.builtin;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.Rule;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a user-supplied Cypher query (from {@code .cvector/rules.yml}) and turns each row into a
 * {@link Violation}. The user is responsible for writing dialect-compatible Cypher — the rule
 * definition can include separate {@code cypher} (Neo4j) and {@code cypherKuzu} (Kuzu) bodies
 * via {@link RulesConfig.CustomRule#getCypherFor(String)}, falling back to the generic
 * {@code cypher} if no per-backend variant is provided.
 */
public class CustomCypherRule implements Rule {

    private final RulesConfig.CustomRule definition;

    public CustomCypherRule(RulesConfig.CustomRule definition) {
        this.definition = definition;
    }

    @Override public String name() { return definition.getName(); }

    @Override public String description() {
        return definition.getDescription() == null ? "" : definition.getDescription();
    }

    @Override public Severity severity() {
        return definition.getSeverity() == null ? Severity.WARN : definition.getSeverity();
    }

    @Override
    public List<Violation> evaluate(String projectId, GraphStore store, RulesConfig cfg) {
        String cypher = definition.getCypherFor(store.backend());
        if (cypher == null || cypher.isBlank()) return List.of();
        Map<String, Object> params = new HashMap<>();
        params.put("pid", projectId);
        var rows = store.rawCypher(cypher, params).rows();
        List<Violation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String subject = stringOrEmpty(r, "subject");
            if (subject.isEmpty()) subject = stringOrEmpty(r, "fqName");
            String message = stringOrEmpty(r, "message");
            if (message.isEmpty()) message = subject;
            Integer line = null;
            Object l = r.get("line");
            if (l instanceof Number n) line = n.intValue();
            String fileId = stringOrEmpty(r, "fileId");
            out.add(new Violation(
                    name(), severity(), subject, message,
                    fileId.isEmpty() ? null : fileId, line
            ));
        }
        return out;
    }

    private static String stringOrEmpty(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
