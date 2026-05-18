package io.doindev.cvector.cli.scan;

import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuBulkLoader;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuIngestor;
import io.doindev.cvector.embedded.KuzuPostScan;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.Neo4jGraphStore;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-process scan loop, extracted from the CLI {@code ScanCommand} so both the CLI and the
 * dashboard's MCP {@code cv_scan_project} tool can drive it without shelling out to a
 * subprocess. Owns nothing of significance — every database handle either comes in via
 * {@link ScanRequest#reusedStore()} or is opened with {@code try-with-resources} for the
 * lifetime of the call.
 *
 * <p><b>Why in-process matters for embedded Kuzu.</b> Kuzu file-locks its database directory.
 * When the dashboard JVM is already running with an open {@link EmbeddedKuzu}, a child
 * {@code cvector scan} process would fail to acquire the same lock and exit immediately — the
 * pattern external MCP agents reported with the previous subprocess implementation. Driving
 * the scan in the same JVM lets it reuse the live handle: every write goes through the same
 * {@link KuzuIngestor} / {@link KuzuBulkLoader} the dashboard already knows about, and the
 * {@code synchronized accept(GraphEvent)} on the Ingestors serialises writes across the scan
 * loop and concurrent MCP tool calls.
 *
 * <p><b>Reused vs opened handles.</b> {@link #scan} compares the request's target DB path
 * (via {@link EmbeddedKuzu#defaultDbPath}) against the reused store's path. They match in
 * two important cases: (1) shared-DB workspaces (every project resolves to the same path),
 * and (2) isolated-DB workspaces where the active project IS the scan target. They differ
 * when an isolated-DB workspace tries to scan a non-active project — in which case the
 * service opens a fresh {@link EmbeddedKuzu} on the foreign project's directory (no lock
 * conflict, since the active project's lock is on a different directory).
 */
@Component
public class InProcessScanService {

    private final List<Parser> parsers;

    public InProcessScanService(List<Parser> parsers) {
        this.parsers = parsers;
    }

    public ScanResult scan(ScanRequest req) throws Exception {
        boolean embedded = resolveEmbedded(req.config());
        return embedded ? scanEmbedded(req) : scanNeo4j(req);
    }

    /**
     * Whether the request's config resolves to embedded Kuzu. Mirrors
     * {@code CvectorRuntime.isEmbeddedRequested} but kept local so the scan package doesn't
     * have a back-edge to the CLI shell.
     */
    private static boolean resolveEmbedded(CvectorConfig cfg) {
        if (Boolean.getBoolean("cvector.embedded")) return true;
        String env = System.getenv("CVECTOR_EMBEDDED");
        if (env != null && env.equalsIgnoreCase("true")) return true;
        String override = System.getProperty("cvector.backend");
        String backend = (override != null && !override.isBlank())
                ? override.toLowerCase()
                : (cfg == null ? CvectorConfig.BACKEND_EMBEDDED : cfg.backendOrDefault());
        return CvectorConfig.BACKEND_EMBEDDED.equals(backend);
    }

    // ===========================================================================================
    //  Neo4j path
    // ===========================================================================================

    private ScanResult scanNeo4j(ScanRequest req) throws Exception {
        ProjectContext ctx = req.context();
        Path scanRoot = req.scanRoot();
        ProgressSink log = ProgressSink.wrap(req.progress());
        ZonedDateTime scanStart = ZonedDateTime.now(ZoneOffset.UTC);
        long startMs = System.currentTimeMillis();

        boolean reused = req.reusedStore() instanceof Neo4jGraphStore;
        Neo4jClient client = reused
                ? ((Neo4jGraphStore) req.reusedStore()).client()
                : openNeo4j(req.config());
        try {
            new SchemaBootstrap(client).bootstrap();
            log.line("backend: neo4j @ " + client.uri());

            int nodesUpserted;
            int edgesUpserted;
            int filesScanned;
            int filesSkipped;
            long parserMs;
            long flushMs;
            try (Ingestor ingestor = new Ingestor(client)) {
                Optional<String> head = GitHelper.head(scanRoot);
                emitProjectNode(ctx, head.orElse(null), ingestor);

                // Mirror the Kuzu incremental-skip path: load existing file hashes and per-file
                // node ownership so unchanged files don't get re-parsed. cleanupStale (timestamp-
                // based on Neo4j) wants to delete nodes whose lastIngestedAt is older than
                // scanStart; we bulk-touch skipped nodes BEFORE cleanup runs so they survive.
                IncrementalState incremental = loadIncrementalStateNeo4j(client, ctx.projectId());
                Set<String> skipTouchedIds = ConcurrentHashMap.newKeySet();

                ScanStats stats = runParsers(ctx, scanRoot, ingestor, incremental.fileSnapshots(), skipTouchedIds, log);
                long flushStart = System.currentTimeMillis();
                ingestor.flush();
                flushMs = System.currentTimeMillis() - flushStart;

                filesScanned = stats.fileCount;
                filesSkipped = stats.skippedCount;
                parserMs = stats.elapsedMs;
                nodesUpserted = ingestor.totalNodes();
                edgesUpserted = ingestor.totalEdges();

                log.line(String.format("scanned %d file(s) in %dms", filesScanned, parserMs + flushMs));
                log.line(String.format("nodes upserted: %d, edges upserted: %d", nodesUpserted, edgesUpserted));

                int linkedNow = resolveDeferredHandlersNeo4j(client, ctx.projectId());
                if (linkedNow > 0) log.line(String.format("linked %d deferred handler(s)", linkedNow));

                int rewiredNow = resolveUnresolvedCallsNeo4j(client, ctx.projectId());
                if (rewiredNow > 0) log.line(String.format("resolved %d cross-file call(s)", rewiredNow));

                int removed = 0;
                if (!req.noClean()) {
                    Set<String> alive = new HashSet<>(skipTouchedIds);
                    if (!alive.isEmpty()) alive.addAll(incremental.sharedNodeIds());
                    if (!alive.isEmpty()) {
                        bulkTouchNodesNeo4j(client, ctx.projectId(), alive, scanStart);
                    }
                    removed = cleanupStaleNeo4j(client, ctx.projectId(), scanStart);
                    if (removed > 0) log.line(String.format("removed %d stale node(s) from prior scans", removed));
                }
                long elapsedMs = System.currentTimeMillis() - startMs;
                return new ScanResult(
                        "neo4j", client.uri(), null,
                        filesScanned, filesSkipped,
                        elapsedMs, parserMs, flushMs,
                        nodesUpserted, edgesUpserted,
                        linkedNow, rewiredNow, removed,
                        stats.parseErrors(),
                        log.snapshot());
            }
        } finally {
            // We only close the client when WE opened it. Reused clients belong to the calling
            // bean's lifecycle (typically the dashboard's restGraphStore) — closing them here
            // would tear down every other consumer in the JVM.
            if (!reused) {
                try { client.close(); } catch (RuntimeException ignored) { /* shutdown best-effort */ }
            }
        }
    }

    private Neo4jClient openNeo4j(CvectorConfig cfg) {
        CvectorConfig.Neo4jConfig n = cfg.neo4jOrDefault();
        return new Neo4jClient(n.uri(), n.user(), n.password());
    }

    // ===========================================================================================
    //  Embedded Kuzu path
    // ===========================================================================================

    /**
     * Embedded scan: streams events into KuzuDB instead of Neo4j, then runs the Kuzu ports of
     * the post-scan reconciliation passes. Picks the bulk-load ingestor (CSV + COPY FROM) when
     * the project's Kuzu rows are empty — ~10-100× faster than per-row MERGE on Windows. Falls
     * back to the MERGE-based ingestor on re-scans.
     */
    private ScanResult scanEmbedded(ScanRequest req) throws Exception {
        ProjectContext ctx = req.context();
        Path scanRoot = req.scanRoot();
        ProgressSink log = ProgressSink.wrap(req.progress());
        long startMs = System.currentTimeMillis();

        Path targetDb = EmbeddedKuzu.defaultDbPath(req.config(), ctx.projectId());
        // Reuse the caller's handle when it points at the exact same DB directory. Comparing
        // absolute+normalised paths avoids false negatives from "./" prefixes or different
        // forward/backslash conventions on Windows.
        Path targetCanonical = canonical(targetDb);
        boolean reuse = req.reusedStore() instanceof KuzuGraphStore kgs
                && targetCanonical.equals(canonical(kgs.kuzu().dbPath()));

        EmbeddedKuzu kuzu = reuse
                ? ((KuzuGraphStore) req.reusedStore()).kuzu()
                : openKuzu(targetDb, req.config());
        try {
            new KuzuSchemaBootstrap(kuzu).bootstrap();
            log.line("backend: embedded kuzu @ " + kuzu.dbPath() + (reuse ? " (reused)" : ""));

            Instant scanStartInstant = Instant.now();
            boolean empty = isKuzuEmpty(kuzu, ctx.projectId());
            String mode = empty ? "bulk" : "merge";
            log.line("ingest mode: " + mode + (empty ? " (empty DB -> COPY FROM)" : " (incremental -> MERGE)"));

            // Re-scan optimisation: load the prior scan's file hashes + shared-node ownership
            // so we can skip parsing any file whose bytes haven't changed since. On bulk
            // (empty-DB) scans there's nothing to load and the skip path is a no-op.
            IncrementalState incremental = empty
                    ? IncrementalState.empty()
                    : loadIncrementalStateEmbedded(kuzu, ctx.projectId());
            Map<String, FileSnapshot> existingFiles = incremental.fileSnapshots();
            Set<String> skipTouchedIds = ConcurrentHashMap.newKeySet();

            int filesScanned;
            int filesSkipped;
            long parserMs;
            long flushMs;
            int nodesUpserted;
            int edgesUpserted;
            int parseErrors;
            Set<String> touchedNodeIds = null;
            try (GraphIngestor ingestor = empty
                    ? new KuzuBulkLoader(kuzu, scanStartInstant)
                    : new KuzuIngestor(kuzu, scanStartInstant)) {
                Optional<String> head = GitHelper.head(scanRoot);
                emitProjectNode(ctx, head.orElse(null), ingestor);

                ScanStats stats = runParsers(ctx, scanRoot, ingestor, existingFiles, skipTouchedIds, log);
                long flushStart = System.currentTimeMillis();
                ingestor.flush();
                flushMs = System.currentTimeMillis() - flushStart;

                filesScanned = stats.fileCount;
                filesSkipped = stats.skippedCount;
                parserMs = stats.elapsedMs;
                parseErrors = stats.parseErrors;
                nodesUpserted = ingestor.totalNodes();
                edgesUpserted = ingestor.totalEdges();

                log.line(String.format("scanned %d file(s) in %dms (parser=%dms, kuzu-flush=%dms)",
                        filesScanned, parserMs + flushMs, parserMs, flushMs));
                log.line(String.format("nodes upserted: %d, edges upserted: %d", nodesUpserted, edgesUpserted));
                if (ingestor instanceof KuzuIngestor ki) touchedNodeIds = ki.touchedNodeIds();
                // Out-of-band size probe — guarantees the 75% / 90% warning fires before
                // this short-lived CLI JVM exits, which can happen well within the
                // monitor's scheduled tick interval on small scans.
                kuzu.checkSizeNow();
            }

            int linkedNow = KuzuPostScan.resolveDeferredHandlers(kuzu, ctx.projectId());
            if (linkedNow > 0) log.line(String.format("linked %d deferred handler(s)", linkedNow));

            int rewiredNow = KuzuPostScan.resolveUnresolvedCalls(kuzu, ctx.projectId());
            if (rewiredNow > 0) log.line(String.format("resolved %d cross-file call(s)", rewiredNow));

            int removed = 0;
            if (!req.noClean() && !empty && touchedNodeIds != null) {
                Set<String> alive = new HashSet<>(touchedNodeIds);
                alive.addAll(skipTouchedIds);
                if (!skipTouchedIds.isEmpty()) alive.addAll(incremental.sharedNodeIds());
                removed = KuzuPostScan.cleanupStale(kuzu, ctx.projectId(), alive);
                if (removed > 0) log.line(String.format("removed %d stale node(s) from prior scans", removed));
            }
            long elapsedMs = System.currentTimeMillis() - startMs;
            return new ScanResult(
                    "embedded", kuzu.dbPath().toString(), mode,
                    filesScanned, filesSkipped,
                    elapsedMs, parserMs, flushMs,
                    nodesUpserted, edgesUpserted,
                    linkedNow, rewiredNow, removed,
                    parseErrors,
                    log.snapshot());
        } finally {
            if (!reuse) {
                try { kuzu.close(); } catch (RuntimeException ignored) { /* shutdown best-effort */ }
            }
        }
    }

    private static EmbeddedKuzu openKuzu(Path db, CvectorConfig cfg) {
        try {
            return new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open embedded kuzu at " + db, e);
        }
    }

    private static Path canonical(Path p) {
        if (p == null) return null;
        try {
            Path abs = p.toAbsolutePath().normalize();
            try { return abs.toRealPath(); }
            catch (IOException ignored) { return abs; }
        } catch (Exception e) {
            return p;
        }
    }

    // ===========================================================================================
    //  Parser walk (shared between embedded + Neo4j)
    // ===========================================================================================

    private ScanStats runParsers(ProjectContext ctx, Path scanRoot, Consumer<GraphEvent> rawSink,
                                 Map<String, FileSnapshot> existingFiles,
                                 Set<String> skipTouchedIds,
                                 ProgressSink log) throws Exception {
        for (Parser p : parsers) p.prepare(ctx);

        // Tee the sink so we can count ParseError nodes as parsers emit them. This is the
        // signal "this file needs attention" (per-file YAML tab errors, malformed JSON, etc.)
        // — surfacing the count in ScanResult lets the dashboard / cv_scan_project response
        // flag dirty scans without forcing callers to query the graph after every run.
        AtomicInteger parseErrorCount = new AtomicInteger();
        Consumer<GraphEvent> sink = ev -> {
            if (ev instanceof GraphEvent.NodeUpsert up && "ParseError".equals(up.key().label())) {
                parseErrorCount.incrementAndGet();
            }
            rawSink.accept(ev);
        };

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

        List<Path> files = collectScanFiles(scanRoot);
        files.parallelStream().forEach(file -> {
            try {
                List<Parser> ps = dispatch(file, byExt, customAccepts);
                if (ps.isEmpty()) return;

                String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
                String currentHash = fileContentHash(file);

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

                if (currentHash != null) {
                    NodeKey fk = new NodeKey(ctx.projectId(), "File", relPath);
                    Map<String, Object> hashProp = new HashMap<>();
                    hashProp.put("fileContentHash", currentHash);
                    sink.accept(new GraphEvent.NodeUpsert(fk, hashProp));
                }
                fileCount.incrementAndGet();
            } catch (Throwable t) {
                log.errorLine(String.format("scan: failed processing %s: %s", file, t));
            }
        });
        for (Parser p : parsers) p.finish();
        int skippedCount = skipped.get();
        if (skippedCount > 0) {
            log.line(String.format("skipped %d unchanged file(s) via contentHash match", skippedCount));
        }
        int parseErrors = parseErrorCount.get();
        if (parseErrors > 0) {
            log.line(String.format("parse errors: %d file(s) need attention — query "
                    + "`MATCH (e {label:\"ParseError\"}) RETURN e.path, e.value` or check cv_health",
                    parseErrors));
        }
        return new ScanStats(fileCount.get(), skippedCount, parseErrors, System.currentTimeMillis() - startMs);
    }

    private record ScanStats(int fileCount, int skippedCount, int parseErrors, long elapsedMs) {}

    public static void emitProjectNode(ProjectContext ctx, String head, Consumer<GraphEvent> sink) {
        NodeKey projectKey = new NodeKey(ctx.projectId(), "Project", ctx.projectId());
        Map<String, Object> props = new HashMap<>();
        props.put("name", ctx.projectName());
        props.put("fqName", ctx.projectId());
        props.put("rootPath", ctx.rootPath().toString());
        if (head != null) props.put("lastScanCommit", head);
        sink.accept(new GraphEvent.NodeUpsert(projectKey, props));
    }

    // ===========================================================================================
    //  File-walk + content hashing
    // ===========================================================================================

    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    private static final ThreadLocal<java.security.MessageDigest> SHA256 =
            ThreadLocal.withInitial(() -> {
                try {
                    return java.security.MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 not available", e);
                }
            });

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

    static List<Path> collectScanFiles(Path scanRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(scanRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!notInIgnored(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && notInIgnored(file)) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.printf("scan: skipping unreadable entry %s: %s%n", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    System.err.printf("scan: incomplete read of %s: %s%n", dir, exc.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    static boolean notInIgnored(Path p) {
        for (Path part : p) {
            String name = part.getFileName().toString();
            switch (name) {
                case "target":
                case "build":
                case "out":
                case "dist":
                case ".next":
                case ".nuxt":
                case ".cache":
                case ".turbo":
                case ".svelte-kit":
                case "node_modules":
                case "vendor":
                case "bower_components":
                case "coverage":
                case "__pycache__":
                case ".tox":
                case ".pytest_cache":
                case ".gradle":
                case ".idea":
                case ".vscode":
                case ".angular":
                case ".git":
                case ".cvector":
                    return false;
                default:
            }
        }
        String full = p.toString().replace('\\', '/');
        if (full.contains("/resources/static/")) return false;
        return true;
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

    // ===========================================================================================
    //  Incremental-state loaders
    // ===========================================================================================

    /**
     * Per-file snapshot of what's in the graph from the prior scan. Used by the content-hash
     * skip path: if a file's bytes hash to {@code contentHash}, we know its parser output would
     * be identical to last scan's, so we can skip parsing entirely and just mark the existing
     * nodes ({@code nodeIds}) as "still alive" via touchedNodeIds.
     */
    record FileSnapshot(String fileId, String contentHash, List<String> nodeIds) {}

    record IncrementalState(Map<String, FileSnapshot> fileSnapshots, Set<String> sharedNodeIds) {
        static IncrementalState empty() { return new IncrementalState(Map.of(), Set.of()); }
    }

    private static IncrementalState loadIncrementalStateEmbedded(EmbeddedKuzu kuzu, String projectId) {
        Map<String, FileSnapshot> snapshots = loadFileSnapshotsEmbedded(kuzu, projectId);
        if (snapshots.isEmpty()) return IncrementalState.empty();
        Set<String> shared = new HashSet<>();
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

    private static Map<String, FileSnapshot> loadFileSnapshotsEmbedded(EmbeddedKuzu kuzu, String projectId) {
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
            ids.add(fid);
            out.put(path.toString(), new FileSnapshot(fid, hash.toString(), ids));
        }
        return out;
    }

    private static boolean isKuzuEmpty(EmbeddedKuzu kuzu, String projectId) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid RETURN count(n) AS c",
                Map.of("pid", projectId));
        if (rows.isEmpty()) return true;
        Object c = rows.get(0).get("c");
        return !(c instanceof Number) || ((Number) c).longValue() == 0;
    }

    /**
     * Neo4j twin of {@link #loadIncrementalStateEmbedded}. Two reads: File nodes with hashes,
     * then the per-file children grouped by {@code fileId}. A third read pulls "shared" nodes
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
        Set<String> shared = new HashSet<>();
        for (var r : sharedRows) {
            Object id = r.get("id").asObject();
            if (id != null) shared.add(id.toString());
        }
        return new IncrementalState(snapshots, shared);
    }

    private static void bulkTouchNodesNeo4j(Neo4jClient client, String projectId,
                                            Set<String> aliveIds, ZonedDateTime now) {
        client.write(
                "UNWIND $ids AS id "
                        + "MATCH (n {projectId: $pid, id: id}) "
                        + "SET n.lastIngestedAt = datetime($now)",
                Map.of("pid", projectId, "ids", new ArrayList<>(aliveIds), "now", now.toString())
        );
    }

    // ===========================================================================================
    //  Post-scan reconciliation (Neo4j)
    // ===========================================================================================

    private static int resolveDeferredHandlersNeo4j(Neo4jClient client, String projectId) {
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

    private static int resolveUnresolvedCallsNeo4j(Neo4jClient client, String projectId) {
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

        var count = client.read(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "RETURN count(placeholder) AS c",
                Map.of("pid", projectId)
        );
        long rewired = count.isEmpty() ? 0 : count.get(0).get("c").asLong();

        client.write(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "DETACH DELETE placeholder",
                Map.of("pid", projectId)
        );
        return (int) rewired;
    }

    private static int cleanupStaleNeo4j(Neo4jClient client, String projectId, ZonedDateTime cutoff) {
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

    // ===========================================================================================
    //  Progress capture
    // ===========================================================================================

    /**
     * Tee progress lines to the caller's consumer AND a captured snapshot. The CLI passes a
     * {@code System.out::println} consumer (lines printed live + captured in the result); the
     * MCP service passes a logfile-appending consumer (lines tee'd to disk + returned in the
     * JSON response). The snapshot ALWAYS captures regardless of whether a consumer was set,
     * so {@link ScanResult#log()} is never null.
     */
    private static final class ProgressSink {
        private final Consumer<String> downstream;
        private final List<String> captured = new ArrayList<>();

        private ProgressSink(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        static ProgressSink wrap(Consumer<String> downstream) {
            return new ProgressSink(downstream);
        }

        synchronized void line(String s) {
            captured.add(s);
            if (downstream != null) downstream.accept(s);
        }

        synchronized void errorLine(String s) {
            captured.add(s);
            System.err.println(s);
        }

        synchronized List<String> snapshot() {
            return new ArrayList<>(captured);
        }
    }
}
