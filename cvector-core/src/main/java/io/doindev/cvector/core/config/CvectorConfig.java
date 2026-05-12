package io.doindev.cvector.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvectorConfig(
        String activeProject,
        Map<String, ProjectEntry> projects,
        Neo4jConfig neo4j
) {

    public CvectorConfig {
        if (projects == null) projects = new LinkedHashMap<>();
    }

    public ProjectEntry active() {
        if (activeProject == null) return null;
        return projects.get(activeProject);
    }

    public record ProjectEntry(String projectId, String name, String rootPath) {}

    public record Neo4jConfig(String uri, String user, String password) {

        public static Neo4jConfig defaults() {
            return new Neo4jConfig("bolt://localhost:7687", "neo4j", "neo4j");
        }
    }
}
