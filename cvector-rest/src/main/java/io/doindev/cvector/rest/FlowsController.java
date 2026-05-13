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

    private static final int MAX_DEPTH = 8;
    private static final int MAX_LIMIT = 500;

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public FlowsController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/flows", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] flows(
            @RequestParam(value = "kind", defaultValue = "all") String kind,
            @RequestParam(value = "depth", defaultValue = "3") int depth,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        // BFS over the call graph is the most expensive query the dashboard runs by far —
        // a 3-hop walk from every REST handler can read thousands of edges. Cache the
        // pre-serialised response so repeat polls hit byte[] without re-walking the graph
        // or re-running Jackson.
        String safeKind = sanitiseKind(kind);
        int effectiveDepth = clamp(depth, 1, MAX_DEPTH);
        int effectiveLimit = clamp(limit, 1, MAX_LIMIT);
        String key = "flows:" + project.projectId() + ":" + safeKind
                + ":d=" + effectiveDepth + ":l=" + effectiveLimit;
        return jsonCache.memoize(key, () -> build(safeKind, effectiveDepth, effectiveLimit));
    }

    private Map<String, Object> build(String kind, int depth, int limit) {
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

    private static String sanitiseKind(String kind) {
        if (kind == null) return "all";
        return switch (kind.toLowerCase()) {
            case "rest", "main", "test", "all" -> kind.toLowerCase();
            default -> "all";
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
