package io.doindev.cvector.core.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LouvainCommunityDetector {

    public record Result(int[] community, double modularity, int communityCount) {}

    private LouvainCommunityDetector() {}

    public static Result detect(int n, List<int[]> directedEdges) {
        return detect(n, directedEdges, false);
    }

    public static Result detectLeiden(int n, List<int[]> directedEdges) {
        return detect(n, directedEdges, true);
    }

    private static Result detect(int n, List<int[]> directedEdges, boolean leidenRefine) {
        if (n == 0) return new Result(new int[0], 0, 0);

        List<Map<Integer, Integer>> adj = buildUndirectedAdjacency(n, directedEdges);
        int[] degree = new int[n];
        int edgeCount = 0;
        for (int i = 0; i < n; i++) {
            int d = 0;
            for (int w : adj.get(i).values()) d += w;
            degree[i] = d;
            edgeCount += d;
        }
        double m2 = edgeCount;
        double m = m2 / 2.0;
        if (m == 0) {
            int[] iso = new int[n];
            for (int i = 0; i < n; i++) iso[i] = i;
            return compact(iso, 0);
        }

        int[] community = new int[n];
        double[] kSum = new double[n];
        for (int i = 0; i < n; i++) {
            community[i] = i;
            kSum[i] = degree[i];
        }
        runLouvainPasses(n, adj, community, kSum, degree, m);

        int[] compactedCommunity = compactCommunityIds(community);
        if (leidenRefine) {
            compactedCommunity = refineDisconnected(n, adj, compactedCommunity);
        }
        double modularity = computeModularity(n, adj, compactedCommunity, degree, m);
        int communityCount = countDistinct(compactedCommunity);
        return new Result(compactedCommunity, modularity, communityCount);
    }

    private static List<Map<Integer, Integer>> buildUndirectedAdjacency(int n, List<int[]> directedEdges) {
        List<Map<Integer, Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new HashMap<>());
        for (int[] e : directedEdges) {
            int u = e[0];
            int v = e[1];
            if (u == v) continue;
            if (u < 0 || u >= n || v < 0 || v >= n) continue;
            adj.get(u).merge(v, 1, Integer::sum);
            adj.get(v).merge(u, 1, Integer::sum);
        }
        return adj;
    }

    private static void runLouvainPasses(int n, List<Map<Integer, Integer>> adj,
                                          int[] community, double[] kSum, int[] degree, double m) {
        boolean improved = true;
        int iter = 0;
        int maxIter = 50;
        while (improved && iter++ < maxIter) {
            improved = singleLouvainPass(n, adj, community, kSum, degree, m);
        }
    }

    private static boolean singleLouvainPass(int n, List<Map<Integer, Integer>> adj,
                                              int[] community, double[] kSum, int[] degree, double m) {
        boolean improved = false;
        for (int i = 0; i < n; i++) {
            int oldComm = community[i];
            double ki = degree[i];

            Map<Integer, Integer> commToWeight = new HashMap<>();
            int kiToOld = 0;
            for (Map.Entry<Integer, Integer> nbr : adj.get(i).entrySet()) {
                int j = nbr.getKey();
                int w = nbr.getValue();
                int c = community[j];
                commToWeight.merge(c, w, Integer::sum);
                if (c == oldComm) kiToOld += w;
            }
            int bestComm = oldComm;
            double bestGain = 0;
            for (Map.Entry<Integer, Integer> e : commToWeight.entrySet()) {
                int c = e.getKey();
                if (c == oldComm) continue;
                int kiToC = e.getValue();
                double kSumC = kSum[c];
                double gain = (kiToC - kiToOld) / m
                        - ki * (kSumC - (kSum[oldComm] - ki)) / (2.0 * m * m);
                if (gain > bestGain) {
                    bestGain = gain;
                    bestComm = c;
                }
            }
            if (bestComm != oldComm) {
                kSum[oldComm] -= ki;
                kSum[bestComm] += ki;
                community[i] = bestComm;
                improved = true;
            }
        }
        return improved;
    }

    private static int[] compactCommunityIds(int[] community) {
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        int[] out = new int[community.length];
        int next = 0;
        for (int i = 0; i < community.length; i++) {
            Integer mapped = remap.get(community[i]);
            if (mapped == null) {
                mapped = next++;
                remap.put(community[i], mapped);
            }
            out[i] = mapped;
        }
        return out;
    }

    private static int[] refineDisconnected(int n, List<Map<Integer, Integer>> adj, int[] community) {
        Map<Integer, List<Integer>> byCommunity = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) byCommunity.computeIfAbsent(community[i], k -> new ArrayList<>()).add(i);

        int[] refined = new int[n];
        Arrays.fill(refined, -1);
        int next = 0;
        for (Map.Entry<Integer, List<Integer>> entry : byCommunity.entrySet()) {
            List<Integer> members = entry.getValue();
            BitSet inCommunity = new BitSet(n);
            for (int m : members) inCommunity.set(m);

            for (int seed : members) {
                if (refined[seed] != -1) continue;
                int label = next++;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(seed);
                refined[seed] = label;
                while (!queue.isEmpty()) {
                    int cur = queue.poll();
                    for (Integer nb : adj.get(cur).keySet()) {
                        if (inCommunity.get(nb) && refined[nb] == -1) {
                            refined[nb] = label;
                            queue.add(nb);
                        }
                    }
                }
            }
        }
        return refined;
    }

    private static double computeModularity(int n, List<Map<Integer, Integer>> adj, int[] community, int[] degree, double m) {
        Map<Integer, Double> commInternal = new HashMap<>();
        Map<Integer, Double> commDegree = new HashMap<>();
        for (int i = 0; i < n; i++) {
            commDegree.merge(community[i], (double) degree[i], Double::sum);
            for (Map.Entry<Integer, Integer> nbr : adj.get(i).entrySet()) {
                int j = nbr.getKey();
                if (j <= i) continue;
                if (community[i] == community[j]) {
                    commInternal.merge(community[i], (double) nbr.getValue(), Double::sum);
                }
            }
        }
        double q = 0;
        for (Map.Entry<Integer, Double> e : commDegree.entrySet()) {
            int c = e.getKey();
            double deg = e.getValue();
            double internal = commInternal.getOrDefault(c, 0.0);
            q += internal / m - (deg / (2.0 * m)) * (deg / (2.0 * m));
        }
        return q;
    }

    private static Result compact(int[] community, double q) {
        int[] compacted = compactCommunityIds(community);
        return new Result(compacted, q, countDistinct(compacted));
    }

    private static int countDistinct(int[] arr) {
        int max = -1;
        for (int v : arr) if (v > max) max = v;
        return max + 1;
    }
}
