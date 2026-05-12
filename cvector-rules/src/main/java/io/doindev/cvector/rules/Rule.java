package io.doindev.cvector.rules;

import io.doindev.cvector.neo4j.repo.GraphQueries;

import java.util.List;

public interface Rule {

    String name();

    String description();

    default Severity severity() { return Severity.ERROR; }

    List<Violation> evaluate(String projectId, GraphQueries queries, RulesConfig config);
}
