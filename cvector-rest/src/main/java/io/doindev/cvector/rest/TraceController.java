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
 * Shortest-path lookups between two symbols across the {@code CALLS} edges. Two endpoints:
 * <ul>
 *   <li>{@code /api/trace?from=A&to=B} — names only (lightweight view of the chain).</li>
 *   <li>{@code /api/path?from=A&to=B} — full node + edge breakdown so the dashboard can render
 *       a per-hop table with labels and relationship types.</li>
 * </ul>
 *
 * <p>Backed by {@link GraphStore#shortestPath} which uses {@code shortestPath()} on Neo4j and
 * a depth-bounded BFS on Kuzu. Cached through {@link GraphReadCache}: the answer is stable
 * until the next scan lands and the same {@code (from, to)} query gets fired repeatedly as
 * the user toggles depth or the dashboard re-polls.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class TraceController {

    private static final int DEFAULT_DEPTH = 6;
    private static final int MAX_DEPTH = 12;

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public TraceController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/trace", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] trace(@RequestParam("from") String from,
                        @RequestParam("to") String to,
                        @RequestParam(value = "depth", defaultValue = "6") int depth) {
        int safeDepth = clamp(depth, 1, MAX_DEPTH);
        String key = "trace:" + project.projectId() + ":" + from + "→" + to + ":d=" + safeDepth;
        return jsonCache.memoize(key, () -> traceImpl(from, to, safeDepth, /*detailed=*/ false));
    }

    @GetMapping(value = "/path", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] path(@RequestParam("from") String from,
                       @RequestParam("to") String to,
                       @RequestParam(value = "depth", defaultValue = "6") int depth) {
        int safeDepth = clamp(depth, 1, MAX_DEPTH);
        String key = "path:" + project.projectId() + ":" + from + "→" + to + ":d=" + safeDepth;
        return jsonCache.memoize(key, () -> traceImpl(from, to, safeDepth, /*detailed=*/ true));
    }

    private Map<String, Object> traceImpl(String from, String to, int depth, boolean detailed) {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", pid, "name", project.name()));
        out.put("from", from);
        out.put("to", to);
        out.put("depth", depth);

        List<Map<String, Object>> sourceHits = store.findSymbol(pid, from);
        List<Map<String, Object>> targetHits = store.findSymbol(pid, to);
        if (sourceHits.isEmpty() || targetHits.isEmpty()) {
            out.put("found", false);
            out.put("reason", sourceHits.isEmpty() ? "source-not-found" : "target-not-found");
            return out;
        }
        Map<String, Object> source = sourceHits.get(0);
        Map<String, Object> target = targetHits.get(0);
        out.put("source", source);
        out.put("target", target);

        Map<String, Object> path = store.shortestPath(pid, (String) source.get("id"), (String) target.get("id"), depth);
        boolean found = Boolean.TRUE.equals(path.get("found"));
        out.put("found", found);
        if (!found) {
            out.put("reason", "no-path");
            return out;
        }
        out.put("pathDepth", path.get("depth"));
        if (detailed) {
            out.put("nodes", path.get("nodes"));
            out.put("edges", path.get("edges"));
        } else {
            // Trace view: just a flat fqName chain.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) path.get("nodes");
            List<Object> chain = new java.util.ArrayList<>(nodes.size());
            for (Map<String, Object> n : nodes) chain.add(n.getOrDefault("fqName", n.getOrDefault("name", "")));
            out.put("chain", chain);
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
