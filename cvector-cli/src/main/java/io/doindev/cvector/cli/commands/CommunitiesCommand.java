package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.doindev.cvector.core.util.UnionFind;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "communities", description = "Detect functional clusters in the call graph.")
public class CommunitiesCommand implements Callable<Integer> {

    @Option(names = "--algorithm",
            description = "leiden (default, recommended), louvain, or connected-components (union-find).")
    private String algorithm = "leiden";

    @Option(names = "--min-size", description = "Minimum community size to report (default 3).")
    private int minSize = 3;

    @Option(names = "--limit", description = "Max communities to print (default 15).")
    private int limit = 15;

    private final CvectorRuntime runtime;

    public CommunitiesCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        try (GraphStore store = runtime.openGraphStore(cfg)) {
            GraphStore.MethodCallGraph g = store.methodCallGraph(active.projectId());
            if (g.fqNames().length == 0) {
                System.out.println("(no Method nodes in graph; run `cvector scan` first)");
                return 0;
            }
            ClusterModel model = clusterMethods(g, algorithm.toLowerCase(Locale.ROOT));
            renderClusters(model);
        }
        return 0;
    }

    private static ClusterModel clusterMethods(GraphStore.MethodCallGraph g, String algorithm) {
        int[] community;
        double modularity;
        switch (algorithm) {
            case "leiden" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detectLeiden(g.fqNames().length, g.edges());
                community = r.community();
                modularity = r.modularity();
            }
            case "louvain" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detect(g.fqNames().length, g.edges());
                community = r.community();
                modularity = r.modularity();
            }
            case "connected-components", "components", "union-find" -> {
                community = unionFindCommunities(g.fqNames().length, g.edges());
                modularity = Double.NaN;
            }
            default -> throw new IllegalArgumentException(
                    "unknown --algorithm: " + algorithm + " (expected leiden, louvain, or connected-components)");
        }

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < community.length; i++) {
            groups.computeIfAbsent(community[i], k -> new ArrayList<>()).add(i);
        }
        int[] internalEdges = new int[Math.max(1, maxId(community) + 1)];
        for (int[] e : g.edges()) {
            if (community[e[0]] == community[e[1]]) internalEdges[community[e[0]]]++;
        }
        return new ClusterModel(g.fqNames(), groups, internalEdges, g.fqNames().length, g.edges().size(), modularity, algorithm);
    }

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

    private static int maxId(int[] community) {
        int m = -1;
        for (int v : community) if (v > m) m = v;
        return m;
    }

    private void renderClusters(ClusterModel model) {
        List<Map.Entry<Integer, List<Integer>>> ordered = new ArrayList<>(model.groups.entrySet());
        ordered.sort(Comparator.<Map.Entry<Integer, List<Integer>>>comparingInt(en -> en.getValue().size()).reversed());

        System.out.printf("algorithm: %s  methods=%d  edges=%d  communities=%d", model.algorithm,
                model.methodCount, model.edgeCount, model.groups.size());
        if (!Double.isNaN(model.modularity)) {
            System.out.printf("  modularity=%.4f", model.modularity);
        }
        System.out.println();

        int shown = 0;
        int total = 0;
        for (Map.Entry<Integer, List<Integer>> en : ordered) {
            List<Integer> members = en.getValue();
            if (members.size() < minSize) continue;
            total++;
            if (shown >= limit) continue;
            shown++;
            renderOneCluster(shown, en.getKey(), members, model);
        }
        System.out.println();
        System.out.printf("communities >= size %d: %d (showing top %d)%n", minSize, total, shown);
    }

    private static void renderOneCluster(int displayIndex, int root, List<Integer> members, ClusterModel model) {
        int n = members.size();
        int internal = root < model.internalEdges.length ? model.internalEdges[root] : 0;
        double maxPossible = n * (n - 1) / 2.0;
        double cohesion = maxPossible > 0 ? internal / maxPossible : 0.0;
        System.out.printf("%n== community #%d  size=%d  internal-edges=%d  cohesion=%.3f ==%n",
                displayIndex, n, internal, cohesion);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < members.size() && i < 10; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("method", model.fqNames[members.get(i)]);
            rows.add(r);
        }
        if (members.size() > 10) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("method", "... (" + (members.size() - 10) + " more)");
            rows.add(r);
        }
        TableRenderer.render(System.out, rows);
    }

    private record ClusterModel(
            String[] fqNames,
            Map<Integer, List<Integer>> groups,
            int[] internalEdges,
            int methodCount,
            int edgeCount,
            double modularity,
            String algorithm
    ) {}
}
