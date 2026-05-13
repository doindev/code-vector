package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cytoscape-friendly subgraph extraction. Given a symbol, walk up to {@code depth} hops in
 * each direction along the CALLS edges and return the discovered nodes + edges in a flat
 * shape the dashboard's Graph view can wrap into Cytoscape elements without further work.
 *
 * <p>BFS performs at most {@code 2 * max + 1} backend reads (one callers + one callees query
 * per frontier node) and caps the result at {@code max} nodes total to keep the wire payload
 * and Cytoscape's layout cost predictable.
 *
 * <p>Endpoint: {@code GET /api/graph/slice?symbol=<fqName>&depth=<1-4>&max=<1-500>&direction=<both|in|out>}
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/graph")
public class GraphSliceController {

    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 4;
    private static final int DEFAULT_MAX = 200;
    private static final int HARD_MAX = 500;

    private final GraphStore store;
    private final ActiveProject project;

    public GraphSliceController(GraphStore restGraphStore, ActiveProject activeProject) {
        this.store = restGraphStore;
        this.project = activeProject;
    }

    @GetMapping("/slice")
    public Map<String, Object> slice(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "depth", defaultValue = "1") int depth,
            @RequestParam(value = "max", defaultValue = "200") int max,
            @RequestParam(value = "direction", defaultValue = "both") String direction
    ) {
        int effectiveDepth = clamp(depth, 1, MAX_DEPTH);
        int effectiveMax = clamp(max, 1, HARD_MAX);
        boolean traverseIn = !"out".equalsIgnoreCase(direction);
        boolean traverseOut = !"in".equalsIgnoreCase(direction);
        String pid = project.projectId();

        List<Map<String, Object>> seeds = store.findSymbol(pid, symbol);
        Map<String, Object> seed = seeds.isEmpty() ? null : seeds.get(0);
        if (seed == null) {
            return emptyResult(symbol);
        }

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        String seedId = String.valueOf(seed.get("id"));
        nodes.put(seedId, projectNode(seed, true));

        // BFS over CALLS in the requested direction(s). Each level pulls callers/callees for
        // every node added in the previous level. Stops early when the result hits effectiveMax
        // -- partial subgraphs are more useful than a 500-error.
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(seedId);
        for (int hop = 0; hop < effectiveDepth && !frontier.isEmpty(); hop++) {
            if (nodes.size() >= effectiveMax) break;
            int frontierSize = frontier.size();
            for (int i = 0; i < frontierSize && nodes.size() < effectiveMax; i++) {
                String currentId = frontier.poll();
                if (currentId == null) break;
                if (traverseIn) {
                    for (Map<String, Object> caller : store.callers(pid, currentId)) {
                        String callerId = String.valueOf(caller.get("id"));
                        if (addNode(nodes, callerId, projectNode(caller, false), effectiveMax)) {
                            frontier.add(callerId);
                        }
                        recordEdge(edges, edgeIds, callerId, currentId, "CALLS");
                    }
                }
                if (traverseOut) {
                    for (Map<String, Object> callee : store.callees(pid, currentId)) {
                        String calleeId = String.valueOf(callee.get("id"));
                        if (addNode(nodes, calleeId, projectNode(callee, false), effectiveMax)) {
                            frontier.add(calleeId);
                        }
                        recordEdge(edges, edgeIds, currentId, calleeId, "CALLS");
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", projectNode(seed, true));
        out.put("nodes", new ArrayList<>(nodes.values()));
        out.put("edges", edges);
        out.put("requested", Map.of(
                "symbol", symbol,
                "depth", effectiveDepth,
                "max", effectiveMax,
                "direction", direction
        ));
        out.put("truncated", nodes.size() >= effectiveMax);
        return out;
    }

    private static boolean addNode(Map<String, Map<String, Object>> nodes,
                                   String id, Map<String, Object> node, int max) {
        if (id == null || id.equals("null")) return false;
        if (nodes.containsKey(id)) return false;
        if (nodes.size() >= max) return false;
        nodes.put(id, node);
        return true;
    }

    private static void recordEdge(List<Map<String, Object>> out, Set<String> seen,
                                   String fromId, String toId, String type) {
        if (fromId == null || toId == null || "null".equals(fromId) || "null".equals(toId)) return;
        String key = fromId + "\u0001" + type + "\u0001" + toId;
        if (!seen.add(key)) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", "e" + (seen.size()));
        e.put("source", fromId);
        e.put("target", toId);
        e.put("type", type);
        out.add(e);
    }

    /**
     * Trim a backend row down to the fields the Graph view actually needs. Avoids leaking
     * 50+ Node-table columns into the JSON payload over the wire.
     */
    private static Map<String, Object> projectNode(Map<String, Object> row, boolean isSeed) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", String.valueOf(row.get("id")));
        n.put("label", row.getOrDefault("label", ""));
        n.put("name", row.getOrDefault("name", ""));
        n.put("fqName", row.getOrDefault("fqName", ""));
        if (row.get("startLine") != null) n.put("startLine", row.get("startLine"));
        if (row.get("fileId") != null) n.put("fileId", row.get("fileId"));
        if (isSeed) n.put("isSeed", true);
        return n;
    }

    private static Map<String, Object> emptyResult(String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", null);
        out.put("nodes", List.of());
        out.put("edges", List.of());
        out.put("requested", Map.of("symbol", symbol));
        out.put("truncated", false);
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
