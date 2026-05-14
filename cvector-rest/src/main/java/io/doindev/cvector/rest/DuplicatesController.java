package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surfaces near-duplicate Method nodes — groups of methods that share
 * {@code (name, paramCount, returnType, ≈ body size)}. This is the "candidates for
 * extraction" view: methods with the same shape across different classes are usually
 * good targets for a shared helper, an abstract base method, or a utility static.
 *
 * <p>{@code GET /api/duplicates?min=2}. Cached through {@link JsonCache}.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class DuplicatesController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public DuplicatesController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/duplicates", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] duplicates(
            @RequestParam(value = "min", defaultValue = "2") int min
    ) {
        int safeMin = Math.max(2, Math.min(min, 20));
        String key = "duplicates:" + project.projectId() + ":min=" + safeMin;
        return jsonCache.memoize(key, () -> build(safeMin));
    }

    private Map<String, Object> build(int min) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));
        out.put("min", min);
        List<Map<String, Object>> groups = store.findDuplicates(project.projectId(), min);
        out.put("groupCount", groups.size());
        out.put("groups", groups);
        return out;
    }
}
