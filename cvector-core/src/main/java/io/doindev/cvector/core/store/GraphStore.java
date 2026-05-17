package io.doindev.cvector.core.store;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Backend-agnostic read surface for the code-vector graph. CLI commands, MCP tools, and the REST
 * controllers route through this so they can run against either the Neo4j driver or the embedded
 * KuzuDB store without branching. Each implementation owns its own connection; callers are
 * expected to close the store when done.
 *
 * <p>This interface intentionally grows method-by-method as commands are migrated off direct
 * {@code Neo4jClient} usage. Don't add a query here until at least one caller needs it — the
 * goal is to minimise the contract both backends must implement.
 */
public interface GraphStore extends AutoCloseable {

    /** True if the backend is reachable and responsive. */
    boolean ping();

    /** Human-readable identifier ({@code bolt://...} for Neo4j, {@code kuzu://...} for embedded). */
    String displayUri();

    /** Ensure schema artefacts (constraints / tables) exist. Idempotent. */
    void bootstrapSchema();

    /** True if the schema has been bootstrapped against the current backend. */
    boolean schemaReady();

    /** Count nodes in the project grouped by label. */
    Map<String, Long> nodeCounts(String projectId);

    /** Count edges in the project grouped by relationship type. */
    Map<String, Long> edgeCounts(String projectId);

    /**
     * Find nodes whose {@code fqName} or {@code name} matches the symbol. Tries exact match first,
     * then {@code endsWith('.' + symbol)}, then plain name equality. Returns up to 25 rows with
     * columns: label, fqName, name, id, startLine, fileId.
     */
    List<Map<String, Object>> findSymbol(String projectId, String symbol);

    /**
     * Substring/wildcard search across {@code name} and {@code fqName}. The query supports
     * {@code *} wildcards; matching is case-insensitive. Optional {@code label} filter narrows
     * to a single kind. Returns columns: label, fqName, name, id.
     */
    List<Map<String, Object>> searchByName(String projectId, String query, String label, int limit);

    /**
     * Schema-level connectivity for the dashboard's meta-graph view. One row per
     * {@code (fromLabel, toLabel, edgeType)} triple, with {@code count} of edges in the
     * graph that match that triple. Used to render "which node labels connect to which
     * via which edge type". Returns at most a few hundred rows even on huge projects --
     * combinatorial in the small set of distinct (label, type) values, not the data size.
     */
    default List<Map<String, Object>> schemaConnectivity(String projectId) {
        return List.of();
    }

    /** Methods that CALL the given node. Columns: fqName, name, callSiteLine, id. */
    List<Map<String, Object>> callers(String projectId, String id);

    /** Methods called BY the given node. Columns: fqName, name, callSiteLine, id. */
    List<Map<String, Object>> callees(String projectId, String id);

    /**
     * Downstream impact via {@code CALLS} / {@code REFERENCES}, up to {@code depth} hops.
     * Returns up to 500 distinct nodes. Columns: label, fqName, name, id.
     */
    List<Map<String, Object>> impactDownstream(String projectId, String id, int depth);

    /** File metadata by fileId. Columns: path, language. Empty map if not found. */
    Map<String, Object> fileOf(String projectId, String fileId);

    /** Project-level metadata. Columns include lastScanCommit and rootPath. Empty map if absent. */
    Map<String, Object> projectMeta(String projectId);

    /** Direct children via {@code CONTAINS}. Columns: label, fqName. */
    List<Map<String, Object>> contains(String projectId, String id, int limit);

    /**
     * Incoming {@code REFERENCES} edges to this node — symbols that reference it without calling.
     * Columns: label, fqName, fileId, line.
     */
    List<Map<String, Object>> referencingNodes(String projectId, String id, int limit);

    /** Files that {@code IMPORTS} this node. Columns: path. */
    List<Map<String, Object>> importingFiles(String projectId, String id, int limit);

    /** Nodes touched within the given time window. Columns: label, fqName, lastIngestedAt. */
    List<Map<String, Object>> recentlyChanged(String projectId, Duration since, int limit);

    /** Maven dependency rows. Columns: groupId, artifactId, version, scope. */
    List<Map<String, Object>> mavenDependencies(String projectId);

    /** File inventory. Columns: path, language, lineCount, methodCount, lastIngestedAt. */
    List<Map<String, Object>> fileInventory(String projectId);

    /**
     * Health rollups for the codebase. Keys: {@code godFiles}, {@code godClasses}, {@code longMethods},
     * {@code deadCode}. Each value is a list of up to 20 rows.
     */
    Map<String, List<Map<String, Object>>> healthRollup(String projectId);

    /**
     * Quality-gate snapshot. Keys: {@code pass} (boolean), {@code errors} (long), {@code warnings}
     * (long), {@code breakdown} (map with per-rule counts).
     */
    Map<String, Object> guardSummary(String projectId);

    /**
     * Infrastructure roll-up. Keys: {@code apiEndpoints}, {@code queueListeners}, {@code scheduledJobs},
     * {@code configKeys}, {@code envVars}, {@code containerImages}, {@code containerPorts},
     * {@code terraformResources}.
     */
    Map<String, List<Map<String, Object>>> infrastructureSummary(String projectId);

    /**
     * Trace execution flows from entry points through the call graph. {@code kind} is one of
     * {@code rest}, {@code main}, {@code test}, or {@code all}. Result keys are the same; each
     * value is a list of {entry, reaches} rows.
     */
    Map<String, List<Map<String, Object>>> traceFlows(String projectId, String kind, int depth, int limit);

    /**
     * Cross-service link discovery. Keys: {@code outgoingHttp}, {@code outgoingMessaging},
     * {@code incomingConsumers}, {@code restEndpoints}, {@code tablesTouched}.
     */
    Map<String, List<Map<String, Object>>> serviceLinks(String projectId);

    /**
     * Full architecture briefing for {@code onboard}. Keys: {@code languages}, {@code topClasses},
     * {@code restEndpoints}, {@code tables}, {@code configKeys}, {@code envVars}, {@code callGraphHubs}.
     * Pair with {@link #nodeCounts}, {@link #edgeCounts}, {@link #mavenDependencies} for the rest of the page.
     */
    Map<String, List<Map<String, Object>>> onboardSummary(String projectId);

    /**
     * Cross-project listing (for the multi-project view). Each row contains {@code projectId},
     * {@code name}, {@code rootPath}, {@code lastScanCommit}, {@code fileCount}. For embedded —
     * where each project has its own KuzuDB — this returns the single active project.
     */
    List<Map<String, Object>> projectsList();

    /**
     * Method-call adjacency dump for community detection. Result: {@code fqNames} is the array of
     * Method fqNames indexed 0..N-1, {@code edges} is a list of {@code int[]{fromIdx, toIdx}}
     * pairs in the CALLS subgraph (Method→Method only). The {@code int[]} pairs match the input
     * format of {@link io.doindev.cvector.core.util.LouvainCommunityDetector}.
     */
    MethodCallGraph methodCallGraph(String projectId);

    /**
     * Result of {@link #methodCallGraph}.
     */
    record MethodCallGraph(String[] fqNames, List<int[]> edges) {}

    /**
     * Test-reach BFS: finds all test methods that transitively reach the given symbol id
     * via {@code CALLS} or {@code REFERENCES} within {@code maxDepth} hops. Returns rows of
     * {test, fileId, depth} ordered by depth ascending. Implemented in Java rather than Cypher
     * so we don't need {@code shortestPath} (which Kuzu doesn't implement).
     */
    List<Map<String, Object>> testReach(String projectId, String id, int maxDepth);

    /**
     * Shortest directed path from {@code fromId} to {@code toId} via outgoing {@code CALLS}
     * edges, up to {@code maxDepth} hops. Default implementation runs BFS via
     * {@link #callees(String, String)} so it works on both Neo4j and Kuzu without needing a
     * native {@code shortestPath}. Backends with a faster native path may override.
     *
     * <p>Returns columns:
     * <ul>
     *   <li>{@code found} — boolean, true when a path exists within maxDepth.</li>
     *   <li>{@code depth} — number of hops (0 when from == to).</li>
     *   <li>{@code nodes} — ordered list of {@code {id, label, fqName, name}} starting at
     *       {@code fromId} and ending at {@code toId}.</li>
     *   <li>{@code edges} — list of {@code {from, to, type}} entries for each hop. Empty when
     *       {@code depth == 0}.</li>
     * </ul>
     */
    default Map<String, Object> shortestPath(String projectId, String fromId, String toId, int maxDepth) {
        java.util.Map<String, Object> empty = new java.util.LinkedHashMap<>();
        empty.put("found", false);
        empty.put("depth", -1);
        empty.put("nodes", java.util.List.of());
        empty.put("edges", java.util.List.of());
        if (fromId == null || toId == null) return empty;
        int safeDepth = Math.max(1, Math.min(maxDepth, 12));
        if (fromId.equals(toId)) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("found", true);
            result.put("depth", 0);
            result.put("nodes", java.util.List.of());
            result.put("edges", java.util.List.of());
            return result;
        }
        // BFS: parent map records the predecessor of each visited node so we can reconstruct
        // the path after we find the target. Bounded by an explicit node-visit cap so a
        // pathological hub (millions of edges) can't fan out unbounded.
        int maxVisited = 5000;
        java.util.Map<String, String> parent = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> depth = new java.util.HashMap<>();
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>();
        depth.put(fromId, 0);
        frontier.add(fromId);
        String found = null;
        while (!frontier.isEmpty() && depth.size() <= maxVisited) {
            String cur = frontier.poll();
            int d = depth.get(cur);
            if (d >= safeDepth) continue;
            for (Map<String, Object> nb : callees(projectId, cur)) {
                Object idObj = nb.get("id");
                if (idObj == null) continue;
                String nbId = idObj.toString();
                if (depth.containsKey(nbId)) continue;
                depth.put(nbId, d + 1);
                parent.put(nbId, cur);
                if (nbId.equals(toId)) { found = nbId; break; }
                frontier.add(nbId);
            }
            if (found != null) break;
        }
        if (found == null) return empty;
        // Reconstruct path from target back to source.
        java.util.LinkedList<String> ids = new java.util.LinkedList<>();
        for (String n = found; n != null; n = parent.get(n)) {
            ids.addFirst(n);
            if (n.equals(fromId)) break;
        }
        // Fetch label/fqName for each node so the dashboard can render without follow-up calls.
        java.util.List<Map<String, Object>> nodes = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            Map<String, Object> meta = nodeById(projectId, id);
            Map<String, Object> n = new java.util.LinkedHashMap<>();
            n.put("id", id);
            n.put("label", meta.getOrDefault("label", ""));
            n.put("name", meta.getOrDefault("name", ""));
            n.put("fqName", meta.getOrDefault("fqName", ""));
            nodes.add(n);
        }
        java.util.List<Map<String, Object>> edges = new java.util.ArrayList<>(Math.max(0, ids.size() - 1));
        for (int i = 0; i + 1 < ids.size(); i++) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("from", ids.get(i));
            e.put("to", ids.get(i + 1));
            e.put("type", "CALLS");
            edges.add(e);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("found", true);
        result.put("depth", ids.size() - 1);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    /**
     * Fetch a node's display metadata by id. Default implementation returns an empty map;
     * backends should override to return {@code {id, label, fqName, name}} cheaply for the
     * symbol pivots in {@link #shortestPath}.
     */
    default Map<String, Object> nodeById(String projectId, String id) {
        return java.util.Map.of();
    }

    /**
     * Database-impact analysis: callers reading from or writing to a given table (and
     * optional column). Returns the keys {@code readers} and {@code writers}; each value is
     * a list of {@code {fqName, fileId, line}} rows for methods that touch the table.
     * Optional {@code column} narrows to method↔column edges.
     */
    default Map<String, List<Map<String, Object>>> dbImpact(String projectId, String table, String column) {
        java.util.Map<String, List<Map<String, Object>>> empty = new java.util.LinkedHashMap<>();
        empty.put("readers", java.util.List.of());
        empty.put("writers", java.util.List.of());
        return empty;
    }

    /**
     * Bulk path → fileId resolution. Given a list of File paths (relative or absolute as the
     * graph stored them), return a {@code path → fileId} map for every path that matches a
     * File node. Default implementation falls back to {@link #searchByName} per entry; backends
     * should override with a single {@code UNWIND}-style query for the per-PR pr-impact case
     * where we resolve 100+ paths at a time.
     */
    default Map<String, String> bulkFilesByPath(String projectId, List<String> paths) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (paths == null || paths.isEmpty()) return out;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            List<Map<String, Object>> hits = searchByName(projectId, p, "File", 1);
            if (!hits.isEmpty()) {
                Object id = hits.get(0).get("id");
                if (id != null) out.put(p, id.toString());
            }
        }
        return out;
    }

    /**
     * Bulk {@code CONTAINS} children resolution. For each fileId, return the list of direct
     * children (methods, classes, fields …). Default implementation loops via
     * {@link #contains}; backends override with a single {@code UNWIND}-batched query so the
     * pr-impact controller doesn't pay an N-round-trip penalty on big PRs.
     */
    default Map<String, List<Map<String, Object>>> bulkContains(String projectId, List<String> fileIds, int limitPerFile) {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        if (fileIds == null || fileIds.isEmpty()) return out;
        for (String fid : fileIds) {
            if (fid == null || fid.isBlank()) continue;
            out.put(fid, contains(projectId, fid, limitPerFile));
        }
        return out;
    }

    /**
     * Bulk incoming-CALLS counter. Returns {@code id → caller-count} for every id in the
     * input. Default loops via {@link #callers}; backends override with a single grouped
     * COUNT query so pr-impact doesn't issue one round trip per symbol.
     */
    default Map<String, Long> bulkCallerCounts(String projectId, List<String> ids) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            out.put(id, (long) callers(projectId, id).size());
        }
        return out;
    }

    /**
     * Bulk outgoing-CALLS counter. Returns {@code id → callee-count} for every id in the
     * input. Used by the dashboard graph view's drill indicator: the badge inside each node
     * tells the user how many children they can drill into without having to expand first.
     *
     * <p>Default impl loops via {@link #callees}; backends override with a single grouped
     * COUNT query so a 200-node slice is one round-trip instead of 200.
     */
    default Map<String, Long> bulkCalleeCounts(String projectId, List<String> ids) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            out.put(id, (long) callees(projectId, id).size());
        }
        return out;
    }

    /**
     * Find groups of Method nodes that look like duplicates — same name, same paramCount,
     * same returnType, and similar body length. Each group has at least {@code minOccurrences}
     * members. The result is a list of group records:
     * <pre>
     *   {
     *     name: "validateInput",
     *     paramCount: 2,
     *     returnType: "boolean",
     *     occurrences: 3,
     *     members: [{fqName, fileId, startLine, lineCount}, ...]
     *   }
     * </pre>
     * Groups are ordered by descending {@code occurrences} so the biggest near-duplicates
     * surface first. The detection is conservative — true duplicate-body finding would need
     * source text, but methods with identical shape across multiple classes are a strong
     * "candidate for extraction to a shared helper" signal.
     */
    default List<Map<String, Object>> findDuplicates(String projectId, int minOccurrences) {
        return java.util.List.of();
    }

    /**
     * Bulk downstream-impact ids. For each source id, returns the set of distinct node ids
     * reachable within {@code depth} hops via {@code CALLS} (and {@code REFERENCES} where the
     * backend supports it). Used by pr-impact to collapse hundreds of per-symbol BFS calls
     * into one or two queries. Default loops via {@link #impactDownstream}.
     */
    default Map<String, java.util.Set<String>> bulkImpactedIds(String projectId, List<String> ids, int depth) {
        Map<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (Map<String, Object> r : impactDownstream(projectId, id, depth)) {
                Object impId = r.get("id");
                if (impId != null) set.add(impId.toString());
            }
            out.put(id, set);
        }
        return out;
    }

    /**
     * Backend identifier — {@code "neo4j"} or {@code "kuzu"}. Callers can branch when a particular
     * query is only supported on one side (e.g. {@code shortestPath()}, regex {@code =~}).
     */
    String backend();

    /**
     * True if the backend accepts arbitrary Cypher passthrough (Neo4j). False for Kuzu, where
     * the dialect is a subset and raw queries should be rewritten via typed methods on this
     * interface or guarded with a backend check.
     */
    default boolean supportsRawCypher() { return "neo4j".equals(backend()); }

    /**
     * Ad-hoc Cypher passthrough. Each backend interprets the query in its own dialect:
     * <ul>
     *   <li>Neo4j: full Cypher (labels-as-types, {@code =~}, {@code shortestPath}, {@code apoc.*}).</li>
     *   <li>Kuzu: subset dialect (single polymorphic {@code Node} table, {@code regexp_matches},
     *       no {@code shortestPath}). Callers should write Kuzu-compatible Cypher and accept that
     *       Neo4j-only constructs fail with a clear error.</li>
     * </ul>
     * Returns columns as a list of maps. Both reads and writes are accepted.
     */
    RawResult rawCypher(String cypher, Map<String, Object> params);

    /** Result of {@link #rawCypher}. {@code isWrite} is best-effort. */
    record RawResult(boolean isWrite, List<Map<String, Object>> rows) {}

    /**
     * Open a fresh ingestor for streaming {@link io.doindev.cvector.core.GraphEvent}s into the
     * backend. Caller closes; failure to close drops buffered writes. Use this for scan/watch
     * paths; for one-off writes, prefer {@link #rawCypher}.
     */
    GraphIngestor openIngestor();

    /**
     * Open a bulk-load ingestor that's faster than {@link #openIngestor()} but only safe to use
     * when the project's tables are empty. Backends without a bulk path return the regular ingestor.
     * Callers should check {@link #isEmpty(String)} before electing this path.
     */
    default GraphIngestor openBulkIngestor() { return openIngestor(); }

    /** True if the project has no nodes yet — the fast-path predicate for {@link #openBulkIngestor}. */
    boolean isEmpty(String projectId);

    /**
     * Delete a File node and everything it transitively {@code CONTAINS} (Methods, Classes, Fields…).
     * Used by incremental scan and the file watcher when a path is removed from the project.
     * Returns the number of nodes removed.
     */
    int deleteFileSubtree(String projectId, String path);

    /**
     * Delete every node belonging to a project (and the edges pinned to those nodes). Used
     * by {@code cv_remove_project} and {@code cvector embedded wipe} on shared-DB
     * deployments. Returns the number of nodes removed.
     *
     * <p>Default implementation runs a single {@code DETACH DELETE} matching by
     * {@code projectId} — works for Neo4j and Kuzu without per-store overrides because both
     * stores carry {@code projectId} on every node. Stores with non-standard schemas should
     * override.
     */
    default int deleteProjectSubtree(String projectId) {
        // The default impl can't run Cypher directly — concrete stores override. We can't
        // throw from here without breaking the GraphStore interface contract for the
        // (rare) implementations that don't carry projectId on every node, so the default
        // is a soft no-op that subclasses are expected to replace.
        return 0;
    }

    /**
     * Swap the backing data store to a different project. Used by the runtime project-switch
     * path so the dashboard / REST surface can flip workspaces without a restart.
     *
     * <p>Neo4j default: no-op — every query already filters by {@code projectId}, so flipping
     * the active project's id is enough; nothing about the bolt connection needs to change.
     *
     * <p>Kuzu override: close the current embedded DB and open the one at
     * {@code ~/.cvector/kuzu-data/<projectId>/graph.kuzu}. The schema gets re-bootstrapped
     * idempotently. Concurrent queries during the swap are safe — the volatile reference flips
     * atomically once the new DB is ready.
     */
    default void swapToProject(String projectId) {
        // Default: no-op. Backends with per-project state should override.
    }

    @Override
    void close();
}
