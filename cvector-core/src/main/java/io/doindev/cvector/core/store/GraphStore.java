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

    @Override
    void close();
}
