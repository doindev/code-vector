package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.doindev.cvector.core.util.UnionFind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Community-detection rollup over the Method-only CALLS subgraph. Mirrors the CLI's
 * {@code cvector communities} command but returns structured JSON for the dashboard. Three
 * algorithms supported: Leiden (default, highest-quality), Louvain (fast), and
 * connected-components (union-find — useful when you want strict "what's reachable" groups).
 *
 * <p>Heavy work lives in the existing {@link LouvainCommunityDetector}; we just pull the
 * adjacency from {@link GraphStore#methodCallGraph} and shape the result. For a typical
 * 2-5k method graph this runs in 100-500 ms.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class CommunitiesController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public CommunitiesController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/communities", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] communities(
            @RequestParam(value = "algorithm", defaultValue = "leiden") String algorithm,
            @RequestParam(value = "minSize", defaultValue = "3") int minSize,
            @RequestParam(value = "limit", defaultValue = "15") int limit,
            @RequestParam(value = "membersPerCommunity", defaultValue = "20") int membersPerCommunity) {
        String cacheKey = "communities:" + project.projectId()
                + ":alg=" + algorithm + ":min=" + minSize + ":lim=" + limit + ":mpc=" + membersPerCommunity;
        return jsonCache.memoize(cacheKey, () -> buildCommunities(algorithm, minSize, limit, membersPerCommunity));
    }

    /** Result of running a community-detection algorithm: assignment array + (optional) modularity score. */
    private record Assignment(int[] community, double modularity) {}

    private Map<String, Object> buildCommunities(String algorithm, int minSize, int limit, int membersPerCommunity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", algorithm);
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));

        GraphStore.MethodCallGraph g = store.methodCallGraph(project.projectId());
        if (g.fqNames().length == 0) return fillEmpty(out);

        Assignment assignment = detect(algorithm, g);
        int[] community = assignment.community();
        int[] internalEdges = countInternalEdges(community, g.edges());
        List<Map.Entry<Integer, List<Integer>>> ordered = orderedCommunities(community);

        int totalAboveMin = 0;
        List<Map<String, Object>> renderedCommunities = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> en : ordered) {
            List<Integer> members = en.getValue();
            if (members.size() < minSize) continue;
            totalAboveMin++;
            if (renderedCommunities.size() >= limit) continue;
            renderedCommunities.add(renderCommunity(en.getKey(), members,
                    internalEdges, g.fqNames(), renderedCommunities.size() + 1, membersPerCommunity));
        }

        out.put("methodCount", g.fqNames().length);
        out.put("edgeCount", g.edges().size());
        out.put("modularity", Double.isNaN(assignment.modularity()) ? null : assignment.modularity());
        out.put("communities", renderedCommunities);
        out.put("totalAboveMinSize", totalAboveMin);
        return out;
    }

    private Assignment detect(String algorithm, GraphStore.MethodCallGraph g) {
        return switch (algorithm.toLowerCase(Locale.ROOT)) {
            case "leiden" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detectLeiden(g.fqNames().length, g.edges());
                yield new Assignment(r.community(), r.modularity());
            }
            case "louvain" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detect(g.fqNames().length, g.edges());
                yield new Assignment(r.community(), r.modularity());
            }
            case "connected-components", "components", "union-find" ->
                    new Assignment(unionFindCommunities(g.fqNames().length, g.edges()), Double.NaN);
            default -> throw new IllegalArgumentException(
                    "unknown algorithm: " + algorithm + " (expected leiden, louvain, or connected-components)");
        };
    }

    private static int[] countInternalEdges(int[] community, List<int[]> edges) {
        int maxId = -1;
        for (int v : community) if (v > maxId) maxId = v;
        int[] counts = new int[Math.max(1, maxId + 1)];
        for (int[] e : edges) if (community[e[0]] == community[e[1]]) counts[community[e[0]]]++;
        return counts;
    }

    /** Group nodes by community then sort by descending size, ties broken by community id. */
    private static List<Map.Entry<Integer, List<Integer>>> orderedCommunities(int[] community) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < community.length; i++) {
            groups.computeIfAbsent(community[i], k -> new ArrayList<>()).add(i);
        }
        List<Map.Entry<Integer, List<Integer>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.<Map.Entry<Integer, List<Integer>>>comparingInt(en -> en.getValue().size()).reversed()
                .thenComparingInt(Map.Entry::getKey));
        return ordered;
    }

    private static Map<String, Object> renderCommunity(int cid, List<Integer> members,
                                                       int[] internalEdges, String[] fqNames,
                                                       int rank, int membersPerCommunity) {
        int internal = cid < internalEdges.length ? internalEdges[cid] : 0;
        double maxPossible = members.size() * (members.size() - 1) / 2.0;
        double cohesion = maxPossible > 0 ? internal / maxPossible : 0.0;
        int show = Math.min(members.size(), Math.max(1, membersPerCommunity));
        List<String> memberNames = new ArrayList<>(show);
        for (int i = 0; i < show; i++) memberNames.add(fqNames[members.get(i)]);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", cid);
        row.put("rank", rank);
        row.put("size", members.size());
        row.put("internalEdges", internal);
        row.put("cohesion", cohesion);
        row.put("members", memberNames);
        row.put("truncated", members.size() > show);
        return row;
    }

    private static Map<String, Object> fillEmpty(Map<String, Object> out) {
        out.put("methodCount", 0);
        out.put("edgeCount", 0);
        out.put("modularity", null);
        out.put("communities", List.of());
        out.put("totalAboveMinSize", 0);
        return out;
    }

    /**
     * Union-find connected components. Lifted from the CLI {@code CommunitiesCommand} so the
     * dashboard doesn't pull in the CLI module. Stable id remap keeps community numbering
     * dense (0..k-1) regardless of original union-find root choices.
     */
    private static int[] unionFindCommunities(int n, List<int[]> edges) {
        UnionFind uf = new UnionFind(n);
        for (int[] e : edges) uf.union(e[0], e[1]);
        int[] community = new int[n];
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        int next = 0;
        for (int i = 0; i < n; i++) {
            int root = uf.find(i);
            Integer mapped = remap.get(root);
            if (mapped == null) {
                mapped = next++;
                remap.put(root, mapped);
            }
            community[i] = mapped;
        }
        return community;
    }
}
