package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class QueryController {

    private static final int MAX_DEPTH = 10;

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public QueryController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    /**
     * Run arbitrary Cypher and return the rows. Used by the dashboard's Query view. We intentionally
     * NOT cache these results — the user is iterating on queries and a stale answer would mislead.
     *
     * <p>Errors (bad syntax, unknown labels, etc.) come back as 400 with the backend's message
     * instead of bubbling up as a 500 so the UI can render them inline.
     *
     * <p>The {@code projectId} param is auto-bound from the active project so the user's Cypher
     * can reference {@code $pid} without having to know the value. Other named params can be
     * passed alongside in the body, though the UI doesn't expose that yet.
     */
    @PostMapping("/query")
    public ResponseEntity<?> runQuery(@RequestBody Map<String, Object> body) {
        Object cypherObj = body == null ? null : body.get("cypher");
        if (!(cypherObj instanceof String cypher) || cypher.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "missing 'cypher' string in request body"));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pid", project.projectId());
        if (body.get("params") instanceof Map<?, ?> userParams) {
            for (Map.Entry<?, ?> e : userParams.entrySet()) {
                if (e.getKey() != null) params.put(e.getKey().toString(), e.getValue());
            }
        }
        try {
            GraphStore.RawResult result = store.rawCypher(cypher, params);
            // The UI expects a plain JSON array; deliver that for read results. For writes,
            // we still return the rows array (often empty) so the UI's row-count badge works.
            return ResponseEntity.ok(result.rows());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("message", msg));
        }
    }

    @GetMapping(value = "/search", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] search(@RequestParam("q") String q) {
        // Search is interactive — every keystroke fires a new query — but the answer for
        // a given (project, query) pair is stable until the next scan lands. Caching
        // gives instant feedback when the user revisits a recent search term.
        String key = "search:" + project.projectId() + ":" + q;
        return jsonCache.memoize(key, () -> {
            List<Map<String, Object>> matches = store.findSymbol(project.projectId(), q);
            return Map.of("query", q, "count", matches.size(), "results", matches);
        });
    }

    @GetMapping(value = "/explain", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] explain(@RequestParam("symbol") String symbol) {
        String key = "explain:" + project.projectId() + ":" + symbol;
        return jsonCache.memoize(key, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
            out.put("query", symbol);
            if (matches.isEmpty()) {
                out.put("found", false);
                return out;
            }
            Map<String, Object> hit = matches.get(0);
            String id = (String) hit.get("id");
            out.put("found", true);
            out.put("symbol", hit);
            if ("Method".equals(hit.get("label"))) {
                out.put("callers", store.callers(project.projectId(), id));
                out.put("callees", store.callees(project.projectId(), id));
            }
            if (hit.get("fileId") != null) {
                out.put("file", store.fileOf(project.projectId(), (String) hit.get("fileId")));
            }
            return out;
        });
    }

    @GetMapping(value = "/impact", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] impact(@RequestParam("symbol") String symbol,
                          @RequestParam(value = "depth", defaultValue = "3") int depth) {
        int effectiveDepth = clamp(depth, 1, MAX_DEPTH);
        String key = "impact:" + project.projectId() + ":" + symbol + ":d=" + effectiveDepth;
        return jsonCache.memoize(key, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
            if (matches.isEmpty()) {
                out.put("found", false);
                out.put("query", symbol);
                return out;
            }
            Map<String, Object> hit = matches.get(0);
            String id = (String) hit.get("id");
            out.put("found", true);
            out.put("symbol", hit);
            out.put("depth", effectiveDepth);
            out.put("impacted", store.impactDownstream(project.projectId(), id, effectiveDepth));
            return out;
        });
    }

    @GetMapping(value = "/test-impact", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] testImpact(@RequestParam("symbol") String symbol,
                              @RequestParam(value = "depth", defaultValue = "5") int depth) {
        int effectiveDepth = clamp(depth, 1, MAX_DEPTH);
        String key = "test-impact:" + project.projectId() + ":" + symbol + ":d=" + effectiveDepth;
        return jsonCache.memoize(key, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
            if (matches.isEmpty()) {
                out.put("found", false);
                out.put("query", symbol);
                return out;
            }
            Map<String, Object> hit = matches.get(0);
            out.put("found", true);
            out.put("symbol", hit);
            out.put("tests", store.testReach(project.projectId(), (String) hit.get("id"), effectiveDepth));
            return out;
        });
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
