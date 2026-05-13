package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
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

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public StatsController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = store.ping();
        out.put("status", ok ? "UP" : "DOWN");
        out.put("backend", store.backend());
        out.put("uri", store.displayUri());
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        return out;
    }

    @GetMapping(value = "/stats", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] stats() {
        return jsonCache.memoize("stats:" + project.projectId(), () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", project.name());
            out.put("projectId", project.projectId());
            out.put("nodes", store.nodeCounts(project.projectId()));
            out.put("edges", store.edgeCounts(project.projectId()));
            return out;
        });
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
