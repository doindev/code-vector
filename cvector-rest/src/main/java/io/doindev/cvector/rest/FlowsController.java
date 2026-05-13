package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry-point trace flows. Surfaces what each REST handler / {@code main} / test method
 * reaches via {@code CALLS} edges up to a bounded depth. Backed by
 * {@link GraphStore#traceFlows} which already applies hard caps (max depth 8, results
 * limited per row to the first 50 reachable methods) so a runaway query can't OOM the
 * dashboard JVM.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class FlowsController {

    private final GraphStore store;
    private final ActiveProject project;

    public FlowsController(GraphStore restGraphStore, ActiveProject activeProject) {
        this.store = restGraphStore;
        this.project = activeProject;
    }

    @GetMapping("/flows")
    public Map<String, Object> flows(
            @RequestParam(value = "kind", defaultValue = "all") String kind,
            @RequestParam(value = "depth", defaultValue = "3") int depth,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Map<String, List<Map<String, Object>>> flows = store.traceFlows(
                project.projectId(), kind, depth, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", project.projectId(),
                "name", project.name()
        ));
        out.put("kind", kind);
        out.put("depth", depth);
        out.put("limit", limit);
        out.put("rest", flows.getOrDefault("rest", List.of()));
        out.put("main", flows.getOrDefault("main", List.of()));
        out.put("test", flows.getOrDefault("test", List.of()));
        return out;
    }
}
