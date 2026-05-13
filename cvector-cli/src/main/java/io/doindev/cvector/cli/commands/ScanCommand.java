package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuBulkLoader;
import io.doindev.cvector.embedded.KuzuIngestor;
import io.doindev.cvector.embedded.KuzuPostScan;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@Command(name = "scan", description = "Scan a path and ingest into Neo4j.", mixinStandardHelpOptions = true)
public class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Directory to scan (default: current directory).")
    private Path path = Paths.get(".");

    @Option(names = "--project", description = "Project name to scan into (defaults to active project).")
    private String project;

    @Option(names = "--no-clean", description = "Skip removing nodes that became stale (default: clean).")
    private boolean noClean;

    private final CvectorRuntime runtime;
    private final List<Parser> parsers;

    public ScanCommand(CvectorRuntime runtime, List<Parser> parsers) {
        this.runtime = runtime;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        if (project != null) {
            if (!cfg.projects().containsKey(project)) {
                System.err.println("project '" + project + "' not found in config");
                return 1;
            }
            cfg = new CvectorConfig(project, cfg.projects(), cfg.neo4j(),
                    cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker());
        }
        ProjectContext ctx = runtime.projectContext(cfg);
        Path scanRoot = path.toAbsolutePath().normalize();

        System.out.println("scanning " + scanRoot + " into project '" + ctx.projectName() + "' (" + ctx.projectId() + ")");
        System.out.println("parsers: " + parsers.stream().map(Parser::name).toList());

        return CvectorRuntime.isEmbeddedRequested()
                ? scanEmbedded(ctx, scanRoot)
                : scanNeo4j(cfg, ctx, scanRoot);
    }

    private Integer scanNeo4j(CvectorConfig cfg, ProjectContext ctx, Path scanRoot) throws Exception {
        ZonedDateTime scanStart = ZonedDateTime.now(ZoneOffset.UTC);
        try (Neo4jClient client = runtime.openNeo4j(cfg);
             Ingestor ingestor = new Ingestor(client)) {
            new SchemaBootstrap(client).bootstrap();

            Optional<String> head = GitHelper.head(scanRoot);
            emitProjectNode(ctx, head.orElse(null), ingestor);

            // Mirror the Kuzu incremental-skip path: load existing file hashes and per-file
            // node ownership so unchanged files don't get re-parsed. cleanupStale (timestamp-
            // based on Neo4j) wants to delete nodes whose lastIngestedAt is older than
            // scanStart; we bulk-touch skipped nodes BEFORE cleanup runs so they survive.
            IncrementalState incremental = loadIncrementalStateNeo4j(client, ctx.projectId());
            java.util.Set<String> skipTouchedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

            ScanStats stats = runParsers(ctx, scanRoot, ingestor, incremental.fileSnapshots(), skipTouchedIds);
            ingestor.flush();

            System.out.printf("scanned %d file(s) in %dms%n", stats.fileCount, stats.elapsedMs);
            System.out.printf("nodes upserted: %d, edges upserted: %d%n", ingestor.totalNodes(), ingestor.totalEdges());

            int linked = resolveDeferredHandlers(client, ctx.projectId());
            if (linked > 0) System.out.printf("linked %d deferred handler(s)%n", linked);

            int rewired = resolveUnresolvedCalls(client, ctx.projectId());
            if (rewired > 0) System.out.printf("resolved %d cross-file call(s)%n", rewired);

            if (!noClean) {
                // Bulk-touch skipped node IDs (and the shared-node pool when any file was
                // skipped) so cleanupStale's timestamp cutoff doesn't sweep them.
                java.util.Set<String> alive = new java.util.HashSet<>(skipTouchedIds);
                if (!alive.isEmpty()) alive.addAll(incremental.sharedNodeIds());
                if (!alive.isEmpty()) {
                    bulkTouchNodesNeo4j(client, ctx.projectId(), alive, scanStart);
                }
                int removed = cleanupStale(client, ctx.projectId(), scanStart);
                if (removed > 0) System.out.printf("removed %d stale node(s) from prior scans%n", removed);
            }
        }
        return 0;
    }

    /**
     * Neo4j twin of {@link #loadIncrementalState}. Two reads: File nodes with hashes, then
     * the per-file children grouped by {@code fileId}. A third read pulls "shared" nodes
     * (no {@code fileId}) so they can be preserved when any file is skipped.
     */
    private static IncrementalState loadIncrementalStateNeo4j(Neo4jClient client, String projectId) {
        var fileRows = client.read(
                "MATCH (f:File {projectId: $pid}) WHERE f.fileContentHash IS NOT NULL "
                        + "RETURN f.path AS path, f.id AS fileId, f.fileContentHash AS hash",
                Map.of("pid", projectId)
        );
        if (fileRows.isEmpty()) return IncrementalState.empty();
        var childRows = client.read(
                "MATCH (n {projectId: $pid}) WHERE n.fileId IS NOT NULL "
                        + "RETURN n.id AS id, n.fileId AS fileId",
                Map.of("pid", projectId)
        );
        Map<String, List<String>> byFileId = new HashMap<>();
        for (var r : childRows) {
            Object fid = r.get("fileId").asObject();
            Object id = r.get("id").asObject();
            if (fid == null || id == null) continue;
            byFileId.computeIfAbsent(fid.toString(), k -> new ArrayList<>()).add(id.toString());
        }
        Map<String, FileSnapshot> snapshots = new HashMap<>();
        for (var r : fileRows) {
            Object path = r.get("path").asObject();
            Object fileId = r.get("fileId").asObject();
            Object hash = r.get("hash").asObject();
            if (path == null || fileId == null || hash == null) continue;
            String fid = fileId.toString();
            List<String> ids = byFileId.getOrDefault(fid, new ArrayList<>());
            ids.add(fid);
            snapshots.put(path.toString(), new FileSnapshot(fid, hash.toString(), ids));
        }
        var sharedRows = client.read(
                "MATCH (n {projectId: $pid}) "
                        + "WHERE n.fileId IS NULL AND NOT (n:File) "
                        + "RETURN n.id AS id",
                Map.of("pid", projectId)
        );
        java.util.Set<String> shared = new java.util.HashSet<>();
        for (var r : sharedRows) {
            Object id = r.get("id").asObject();
            if (id != null) shared.add(id.toString());
        }
        return new IncrementalState(snapshots, shared);
    }

    /**
     * Bulk-update {@code lastIngestedAt} for every node whose id is in {@code aliveIds}.
     * Run before {@code cleanupStale} so its timestamp-cutoff predicate spares these nodes.
     */
    private static void bulkTouchNodesNeo4j(Neo4jClient client, String projectId,
                                            java.util.Set<String> aliveIds, ZonedDateTime now) {
        client.write(
                "UNWIND $ids AS id "
                        + "MATCH (n {projectId: $pid, id: id}) "
                        + "SET n.lastIngestedAt = datetime($now)",
                Map.of("pid", projectId, "ids", new ArrayList<>(aliveIds), "now", now.toString())
        );
    }

    /**
     * Embedded scan path: streams events into KuzuDB instead of Neo4j, then runs the Kuzu ports
     * of the post-scan reconciliation passes. Picks the bulk-load ingestor (CSV + COPY FROM)
     * when the project's Kuzu DB is empty — ~10-100× faster than per-row MERGE on Windows. Falls
     * back to the MERGE-based ingestor on re-scans.
     */
    private Integer scanEmbedded(ProjectContext ctx, Path scanRoot) throws Exception {
        Path db = EmbeddedKuzu.defaultDbPath(ctx.projectId());
        System.out.println("backend: embedded kuzu @ " + db);
        java.time.Instant scanStartInstant = java.time.Instant.now();
        try (EmbeddedKuzu kuzu = new EmbeddedKuzu(db)) {
            new KuzuSchemaBootstrap(kuzu).bootstrap();
            boolean empty = isKuzuEmpty(kuzu);
            String mode = empty ? "bulk" : "merge";
            System.out.println("ingest mode: " + mode + (empty ? " (empty DB → COPY FROM)" : " (incremental → MERGE)"));

            // Re-scan optimisation: load the prior scan's file hashes + shared-node ownership
            // so we can skip parsing any file whose bytes haven't changed since. On bulk
            // (empty-DB) scans there's nothing to load and the skip path is a no-op.
            IncrementalState incremental = empty ? IncrementalState.empty() : loadIncrementalState(kuzu, ctx.projectId());
            Map<String, FileSnapshot> existingFiles = incremental.fileSnapshots();
            java.util.Set<String> skipTouchedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

            java.util.Set<String> touchedNodeIds = null;
            try (GraphIngestor ingestor = empty
                    ? new KuzuBulkLoader(kuzu, scanStartInstant)
                    : new KuzuIngestor(kuzu, scanStartInstant)) {
                Optional<String> head = GitHelper.head(scanRoot);
                emitProjectNode(ctx, head.orElse(null), ingestor);

                ScanStats stats = runParsers(ctx, scanRoot, ingestor, existingFiles, skipTouchedIds);
                long flushStart = System.currentTimeMillis();
                ingestor.flush();
                long flushMs = System.currentTimeMillis() - flushStart;

                System.out.printf("scanned %d file(s) in %dms (parser=%dms, kuzu-flush=%dms)%n",
                        stats.fileCount, stats.elapsedMs + flushMs, stats.elapsedMs, flushMs);
                System.out.printf("nodes upserted: %d, edges upserted: %d%n",
                        ingestor.totalNodes(), ingestor.totalEdges());
                if (ingestor instanceof KuzuIngestor ki) touchedNodeIds = ki.touchedNodeIds();
            }

            int linked = KuzuPostScan.resolveDeferredHandlers(kuzu, ctx.projectId());
            if (linked > 0) System.out.printf("linked %d deferred handler(s)%n", linked);

            int rewired = KuzuPostScan.resolveUnresolvedCalls(kuzu, ctx.projectId());
            if (rewired > 0) System.out.printf("resolved %d cross-file call(s)%n", rewired);

            // Only run cleanup on the merge path; on the empty-DB bulk path everything is fresh by
            // definition. cleanupStale now takes the ingestor's set of touched ids (every node it
            // saw during the scan, written or skipped) rather than a timestamp cutoff — see
            // KuzuPostScan.cleanupStale's javadoc for why.
            if (!noClean && !empty && touchedNodeIds != null) {
                // Merge skip-path touched IDs into the ingestor's view -- nodes from
                // hash-skipped files weren't actually written this scan, but they're still
                // current and should not be cleaned up. When any file is skipped we also
                // preserve the "shared" nodes (Module / ApiEndpoint / Table / etc.) that
                // parsers emit without a fileId -- the skipped files' parsers never re-emit
                // them this scan but they're still semantically alive.
                java.util.Set<String> alive = new java.util.HashSet<>(touchedNodeIds);
                alive.addAll(skipTouchedIds);
                if (!skipTouchedIds.isEmpty()) alive.addAll(incremental.sharedNodeIds());
                int removed = KuzuPostScan.cleanupStale(kuzu, ctx.projectId(), alive);
                if (removed > 0) System.out.printf("removed %d stale node(s) from prior scans%n", removed);
            }
        }
        return 0;
    }

    /**
     * SHA-256 of a file's bytes, rendered as 16-char lowercase hex. Cheap enough to run on every
     * file in parallel during the scan walk -- a 10k-line source file hashes in low single-digit
     * milliseconds, two orders of magnitude faster than ANTLR parsing it. Returns null on read
     * failure so the caller can fall back to parsing without poisoning the skip cache.
     */
    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    /**
     * ThreadLocal MessageDigest skips the per-call JCA provider lookup — meaningful on the
     * parallel scan path where this can be invoked once per file (355+ on cvector itself).
     */
    private static final ThreadLocal<java.security.MessageDigest> SHA256 =
            ThreadLocal.withInitial(() -> {
                try {
                    return java.security.MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 not available", e);
                }
            });

    /**
     * Short content-hash for the per-file incremental-skip path. 16-hex-char = first 8 bytes of
     * SHA-256 of the file's bytes. Inline hex encoding skips the {@code String.format("%02x",…)}
     * cost; the ThreadLocal digest skips the JCA lookup. Together these shave noticeable time
     * off a full scan since the function is on the parallel hot path.
     */
    private static String fileContentHash(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            java.security.MessageDigest md = SHA256.get();
            md.reset();
            byte[] digest = md.digest(bytes);
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                int b = digest[i] & 0xFF;
                out[i * 2]     = HEX_ALPHABET[b >>> 4];
                out[i * 2 + 1] = HEX_ALPHABET[b & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pre-load the prior scan's per-file snapshot from the embedded Kuzu DB. Returns
     * {@code relPath -> (fileId, contentHash, [nodeIds])} for every File node that has a
     * recorded {@code contentHash}. The list of node IDs includes the File node itself plus
     * every descendant node ({@code n.fileId == f.id}), which is what the skip path needs to
     * preserve from stale-cleanup. Two reads (instead of a chained OPTIONAL MATCH) because
     * Kuzu's binder has restrictions on outer-bound vars inside optional patterns.
     */
    /**
     * Pre-load both per-file ownership and the set of shared nodes for the incremental skip.
     * One pass over the existing graph; results are merged in Java.
     */
    private static IncrementalState loadIncrementalState(EmbeddedKuzu kuzu, String projectId) {
        Map<String, FileSnapshot> snapshots = loadFileSnapshots(kuzu, projectId);
        if (snapshots.isEmpty()) return IncrementalState.empty();
        java.util.Set<String> shared = new java.util.HashSet<>();
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid AND n.fileId IS NULL AND n.label <> 'File' "
                        + "RETURN n.id AS id",
                Map.of("pid", projectId)
        );
        for (Map<String, Object> r : rows) {
            Object id = r.get("id");
            if (id != null) shared.add(id.toString());
        }
        return new IncrementalState(snapshots, shared);
    }

    private static Map<String, FileSnapshot> loadFileSnapshots(EmbeddedKuzu kuzu, String projectId) {
        List<Map<String, Object>> fileRows = kuzu.read(
                "MATCH (f:Node) WHERE f.projectId = $pid AND f.label = 'File' AND f.fileContentHash IS NOT NULL "
                        + "RETURN f.path AS path, f.id AS fileId, f.fileContentHash AS hash",
                Map.of("pid", projectId)
        );
        if (fileRows.isEmpty()) return Map.of();
        List<Map<String, Object>> childRows = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid AND n.fileId IS NOT NULL "
                        + "RETURN n.id AS id, n.fileId AS fileId",
                Map.of("pid", projectId)
        );
        // Group children by fileId for O(1) lookup.
        Map<String, List<String>> byFileId = new HashMap<>();
        for (Map<String, Object> r : childRows) {
            Object fid = r.get("fileId");
            Object id = r.get("id");
            if (fid == null || id == null) continue;
            byFileId.computeIfAbsent(fid.toString(), k -> new ArrayList<>()).add(id.toString());
        }
        Map<String, FileSnapshot> out = new HashMap<>();
        for (Map<String, Object> r : fileRows) {
            Object path = r.get("path");
            Object fileId = r.get("fileId");
            Object hash = r.get("hash");
            if (path == null || fileId == null || hash == null) continue;
            String fid = fileId.toString();
            List<String> ids = byFileId.getOrDefault(fid, new ArrayList<>());
            ids.add(fid); // include the File node itself
            out.put(path.toString(), new FileSnapshot(fid, hash.toString(), ids));
        }
        return out;
    }

    private static boolean isKuzuEmpty(EmbeddedKuzu kuzu) {
        List<Map<String, Object>> rows = kuzu.read("MATCH (n:Node) RETURN count(n) AS c");
        if (rows.isEmpty()) return true;
        Object c = rows.get(0).get("c");
        return !(c instanceof Number) || ((Number) c).longValue() == 0;
    }

    /**
     * Per-file snapshot of what's in the graph from the prior scan. Used by the content-hash
     * skip path: if a file's bytes hash to {@link #contentHash}, we know its parser output
     * would be identical to last scan's, so we can skip parsing entirely and just mark the
     * existing nodes ({@link #nodeIds}) as "still alive" via touchedNodeIds.
     */
    record FileSnapshot(String fileId, String contentHash, List<String> nodeIds) {}

    /**
     * Result of pre-loading the prior scan's state for the incremental-skip path. Holds
     * per-file ownership ({@code fileSnapshots}) and a separate set of "shared" node IDs
     * (Modules, ApiEndpoints, Tables, etc.) that the parsers emit without a {@code fileId}
     * tying them back to a single source file. Those shared nodes need to survive cleanup
     * whenever any file is skipped -- otherwise the stale-cleanup pass deletes them because
     * the skipped files' parsers never re-emitted them this scan.
     */
    record IncrementalState(Map<String, FileSnapshot> fileSnapshots, java.util.Set<String> sharedNodeIds) {
        static IncrementalState empty() { return new IncrementalState(Map.of(), java.util.Set.of()); }
    }

    /**
     * Parser-walk entry point with optional incremental-skip support. When {@code existingFiles}
     * is non-empty, each file's SHA-256 is computed and compared against the snapshot from the
     * previous scan; matches skip parsing entirely, and the file's existing node IDs are added
     * to {@code skipTouchedIds} so the post-scan stale-cleanup pass doesn't delete them.
     */
    private ScanStats runParsers(ProjectContext ctx, Path scanRoot, Consumer<GraphEvent> sink,
                                 Map<String, FileSnapshot> existingFiles,
                                 java.util.Set<String> skipTouchedIds) throws Exception {
        for (Parser p : parsers) p.prepare(ctx);

        // Pre-compute extension -> parsers map so dispatch is O(1) per file instead of O(N parsers).
        // Multiple parsers may claim the same extension (e.g. TypeScript + JavaScript both claim .js,
        // a future Vue parser registers .vue alongside others). All matching parsers run on each file;
        // the graph layer's NodeUpsert/EdgeUpsert are idempotent on NodeKey/EdgeKey, so emitting
        // overlapping nodes/edges collapses into one (with property maps merged last-write-wins).
        // Parsers with a custom accepts() (Dockerfile, maven-pom filename filter) are tried per file.
        Map<String, List<Parser>> byExt = new HashMap<>();
        List<Parser> customAccepts = new ArrayList<>();
        for (Parser p : parsers) {
            if (hasCustomAccepts(p)) {
                customAccepts.add(p);
            } else {
                for (String ext : p.supportedExtensions()) {
                    byExt.computeIfAbsent(ext.toLowerCase(), k -> new ArrayList<>()).add(p);
                }
            }
        }

        AtomicInteger fileCount = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        long startMs = System.currentTimeMillis();
        boolean canSkip = !existingFiles.isEmpty() && skipTouchedIds != null;
        // Parallel walk: parsers were audited for thread safety (each `parse()` keeps state local,
        // class-level mutable caches use ConcurrentHashMap, JavaParser is held in a ThreadLocal).
        // The sink (`Ingestor` / `KuzuIngestor`) serialises via `synchronized accept`, so concurrent
        // emits are safe; the speedup comes from parallel AST construction across worker threads.
        try (Stream<Path> walk = Files.walk(scanRoot)) {
            walk.parallel()
                    .filter(Files::isRegularFile)
                    .filter(ScanCommand::notInIgnored)
                    .forEach(file -> {
                        List<Parser> ps = dispatch(file, byExt, customAccepts);
                        if (ps.isEmpty()) return;

                        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

                        // Compute the file's content hash exactly once per scan. Used by both the
                        // skip-check (if a prior hash matches we don't re-parse) and the post-scan
                        // stamp (so the NEXT scan can skip this file). Previously the function
                        // was called twice per file — duplicated I/O + SHA-256 on a parallel hot
                        // path. Single-call version saves the second read + digest.
                        String currentHash = fileContentHash(file);

                        // Content-hash skip: if a prior scan recorded this exact bytes-hash for
                        // this file, the parser output would be byte-identical, so re-parsing is
                        // pure waste. Mark the prior nodes as still-alive and move on.
                        if (canSkip) {
                            FileSnapshot prior = existingFiles.get(relPath);
                            if (prior != null && prior.contentHash() != null
                                    && currentHash != null && currentHash.equals(prior.contentHash())) {
                                skipTouchedIds.addAll(prior.nodeIds());
                                skipped.incrementAndGet();
                                fileCount.incrementAndGet();
                                return;
                            }
                        }

                        for (Parser p : ps) p.parse(file, ctx, sink);

                        // Stamp the file's contentHash so the NEXT scan can skip it. We piggyback
                        // on whichever File NodeKey the parsers produced — all parsers use the
                        // same identity (projectId, "File", relPath) by convention, so this
                        // NodeUpsert merges into the existing record's property map.
                        if (currentHash != null) {
                            NodeKey fk = new NodeKey(ctx.projectId(), "File", relPath);
                            Map<String, Object> hashProp = new HashMap<>();
                            // Use fileContentHash, NOT contentHash -- the latter is the per-node
                            // property-map hash that the ingestor computes internally.
                            hashProp.put("fileContentHash", currentHash);
                            sink.accept(new GraphEvent.NodeUpsert(fk, hashProp));
                        }
                        fileCount.incrementAndGet();
                    });
        }
        for (Parser p : parsers) p.finish();
        int skippedCount = skipped.get();
        if (skippedCount > 0) {
            System.out.printf("skipped %d unchanged file(s) via contentHash match%n", skippedCount);
        }
        return new ScanStats(fileCount.get(), System.currentTimeMillis() - startMs);
    }

    private record ScanStats(int fileCount, long elapsedMs) {}

    /**
     * Resolve cross-file handler references that the per-file parsers couldn't link directly.
     * Currently: Django ApiEndpoints carrying a {@code viewRef} like {@code views.user_detail}
     * get a {@code HANDLES} edge to any Method whose name matches the last dotted segment.
     */
    private static int resolveDeferredHandlers(Neo4jClient client, String projectId) {
        var rows = client.read(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "WHERE e.viewRef IS NOT NULL AND NOT EXISTS { MATCH (e)-[:HANDLES]->() } "
                        + "WITH e, split(e.viewRef, '.') AS parts "
                        + "WITH e, parts[size(parts)-1] AS handlerName "
                        + "MATCH (m:Method {projectId: $pid, name: handlerName}) "
                        + "RETURN count(m) AS pending",
                Map.of("pid", projectId)
        );
        long pending = rows.isEmpty() ? 0 : rows.get(0).get("pending").asLong();
        if (pending == 0) return 0;
        client.write(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "WHERE e.viewRef IS NOT NULL AND NOT EXISTS { MATCH (e)-[:HANDLES]->() } "
                        + "WITH e, split(e.viewRef, '.') AS parts "
                        + "WITH e, parts[size(parts)-1] AS handlerName "
                        + "MATCH (m:Method {projectId: $pid, name: handlerName}) "
                        + "MERGE (e)-[:HANDLES]->(m)",
                Map.of("pid", projectId)
        );
        return (int) pending;
    }

    /**
     * Post-scan resolution: parsers emit cross-file calls as `unresolved.<name>:<arity>` placeholder
     * Method nodes. This pass rewires CALLS edges that target such placeholders to the real project
     * Method node(s) with matching simple name + parameter count, then deletes placeholders whose
     * incoming edges were all rewired. Replaces JavaSymbolSolver-based per-call resolution which was
     * by far the hottest path in the previous parse loop.
     */
    private static int resolveUnresolvedCalls(Neo4jClient client, String projectId) {
        // Phase 1: rewire placeholders with exactly one project-method candidate (confident match).
        client.write(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND placeholder.fqName CONTAINS ':' "
                        + "WITH placeholder, "
                        + "     split(placeholder.fqName, ':')[size(split(placeholder.fqName, ':'))-1] AS arityStr "
                        + "WITH placeholder, placeholder.name AS name, toInteger(arityStr) AS arity "
                        + "MATCH (cand:Method {projectId: $pid, name: name, paramCount: arity}) "
                        + "WHERE NOT cand.fqName STARTS WITH 'unresolved.' "
                        + "WITH placeholder, collect(cand) AS candidates "
                        + "WHERE size(candidates) = 1 "
                        + "WITH placeholder, candidates[0] AS resolved "
                        + "MATCH (caller)-[old:CALLS]->(placeholder) "
                        + "WITH caller, old, placeholder, resolved, properties(old) AS oldProps "
                        + "MERGE (caller)-[new:CALLS]->(resolved) "
                        + "SET new += oldProps, new.confidence = coalesce(oldProps.confidence, 0.5) + 0.2 "
                        + "DELETE old",
                Map.of("pid", projectId)
        );

        // Phase 2: count placeholders that were fully rewired (now orphaned) for the user-facing report.
        var count = client.read(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "RETURN count(placeholder) AS c",
                Map.of("pid", projectId)
        );
        long rewired = count.isEmpty() ? 0 : count.get(0).get("c").asLong();

        // Phase 3: drop orphaned placeholders.
        client.write(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "DETACH DELETE placeholder",
                Map.of("pid", projectId)
        );
        return (int) rewired;
    }

    /** Single-pass stale node cleanup. Replaces 13 per-label read+write round-trips with one count + one delete. */
    private static int cleanupStale(Neo4jClient client, String projectId, ZonedDateTime cutoff) {
        List<String> labels = List.of("Method", "Class", "Field", "File", "ApiEndpoint",
                "ConfigKey", "EnvVar", "MavenDependency", "Table", "Column",
                "CssClass", "DesignToken", "CssMixin");
        String labelPredicate = labels.stream()
                .map(l -> "n:" + l)
                .reduce((a, b) -> a + " OR " + b)
                .orElse("false");
        var rows = client.read(
                "MATCH (n) "
                        + "WHERE n.projectId = $pid "
                        + "AND (" + labelPredicate + ") "
                        + "AND n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt < datetime($cutoff) "
                        + "RETURN count(n) AS c",
                Map.of("pid", projectId, "cutoff", cutoff.toString())
        );
        long count = rows.isEmpty() ? 0L : rows.get(0).get("c").asLong();
        if (count == 0) return 0;
        client.write(
                "MATCH (n) "
                        + "WHERE n.projectId = $pid "
                        + "AND (" + labelPredicate + ") "
                        + "AND n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt < datetime($cutoff) "
                        + "DETACH DELETE n",
                Map.of("pid", projectId, "cutoff", cutoff.toString())
        );
        return (int) count;
    }

    private static boolean hasCustomAccepts(Parser p) {
        try {
            return p.getClass().getDeclaredMethod("accepts", Path.class) != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static List<Parser> dispatch(Path file, Map<String, List<Parser>> byExt, List<Parser> customAccepts) {
        List<Parser> hits = new ArrayList<>();
        for (Parser p : customAccepts) {
            if (p.accepts(file)) hits.add(p);
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            List<Parser> extHits = byExt.get(name.substring(dot + 1).toLowerCase());
            if (extHits != null) hits.addAll(extHits);
        }
        return hits;
    }

    static void emitProjectNode(ProjectContext ctx, String head, Consumer<GraphEvent> sink) {
        NodeKey projectKey = new NodeKey(ctx.projectId(), "Project", ctx.projectId());
        Map<String, Object> props = new HashMap<>();
        props.put("name", ctx.projectName());
        props.put("fqName", ctx.projectId());
        props.put("rootPath", ctx.rootPath().toString());
        if (head != null) props.put("lastScanCommit", head);
        sink.accept(new GraphEvent.NodeUpsert(projectKey, props));
    }

    private static boolean notInIgnored(Path p) {
        for (Path part : p) {
            String name = part.getFileName().toString();
            // Standard build / dependency / cache / coverage directories. Walking these is
            // pure waste -- the contents are either binary, generated, vendored, or pinned
            // from package managers and rarely contain code the user wants the graph to know
            // about. Each name match avoids parsing potentially thousands of files.
            switch (name) {
                case "target":      // Maven / Cargo build output
                case "build":       // Gradle / generic
                case "out":         // IDE build dirs (IntelliJ, .NET, etc.)
                case "dist":        // JS bundlers (webpack, vite, rollup)
                case ".next":       // Next.js
                case ".nuxt":       // Nuxt
                case ".cache":      // Parcel, Gatsby, npm
                case ".turbo":      // Turborepo
                case ".svelte-kit": // SvelteKit
                case "node_modules":// npm / yarn / pnpm dependencies
                case "vendor":      // Composer, Go modules vendoring, Ruby gems
                case "bower_components": // legacy JS dependencies
                case "coverage":    // jest / istanbul / pytest-cov / etc.
                case "__pycache__": // Python compiled cache
                case ".tox":        // tox virtualenvs
                case ".pytest_cache":
                case ".gradle":     // Gradle daemon cache
                case ".idea":       // JetBrains IDE metadata
                case ".vscode":     // VS Code metadata
                case ".git":
                case ".cvector":
                    return false;
                default:
                    // continue scanning path components
            }
        }
        return true;
    }
}
