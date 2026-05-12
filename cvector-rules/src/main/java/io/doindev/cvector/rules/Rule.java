package io.doindev.cvector.rules;

import io.doindev.cvector.core.store.GraphStore;

import java.util.List;

public interface Rule {

    String name();

    String description();

    default Severity severity() { return Severity.ERROR; }

    /**
     * Evaluate the rule against the active project. Implementations should use
     * {@link GraphStore} methods where possible; for queries that don't have a typed equivalent,
     * use {@link GraphStore#rawCypher} and branch on {@link GraphStore#backend()} to emit
     * dialect-correct Cypher (Neo4j and Kuzu schemas differ — Neo4j has first-class node labels
     * while Kuzu's polymorphic Node table uses a {@code label} property column).
     */
    List<Violation> evaluate(String projectId, GraphStore store, RulesConfig config);
}
