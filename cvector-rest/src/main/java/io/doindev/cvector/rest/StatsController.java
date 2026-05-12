package io.doindev.cvector.rest;

import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class StatsController {

    private final GraphQueries queries;
    private final Neo4jClient client;
    private final ActiveProject project;

    public StatsController(GraphQueries restGraphQueries, Neo4jClient restNeo4jClient, ActiveProject activeProject) {
        this.queries = restGraphQueries;
        this.client = restNeo4jClient;
        this.project = activeProject;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = client.ping();
        out.put("status", ok ? "UP" : "DOWN");
        out.put("neo4j", client.uri());
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        out.put("nodes", queries.nodeCounts(project.projectId()));
        out.put("edges", queries.edgeCounts(project.projectId()));
        return out;
    }

    @GetMapping("/projects")
    public Map<String, Object> projects() {
        return Map.of(
                "active", Map.of(
                        "projectId", project.projectId(),
                        "name", project.name(),
                        "rootPath", project.rootPath()
                )
        );
    }
}
