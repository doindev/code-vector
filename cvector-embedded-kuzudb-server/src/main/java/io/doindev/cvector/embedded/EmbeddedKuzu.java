package io.doindev.cvector.embedded;

import com.kuzudb.Connection;
import com.kuzudb.Database;
import com.kuzudb.DataTypeID;
import com.kuzudb.FlatTuple;
import com.kuzudb.PreparedStatement;
import com.kuzudb.QueryResult;
import com.kuzudb.Value;
import io.doindev.cvector.core.config.CvectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded graph store using KuzuDB. One {@link Database} + {@link Connection} per project. Owns a
 * single database directory at {@code ~/.cvector/kuzu-data/<projectId>/}. Unlike a server-based
 * Neo4j, opening this class IS the database — no process to spawn, no port to bind.
 *
 * <p>API surface mirrors {@code Neo4jClient} where it can:
 * <ul>
 *   <li>{@link #ping()} — does the database open and respond?</li>
 *   <li>{@link #read(String, Map)} — read query, returns rows as generic maps</li>
 *   <li>{@link #write(String, Map)} — write query, no result</li>
 *   <li>{@link #close()} — close connection and database</li>
 * </ul>
 *
 * <p>Kuzu's Cypher dialect is a strict subset of Neo4j's. Notable differences callers must handle:
 * <ul>
 *   <li>Node/relationship tables must be declared in advance via {@link KuzuSchemaBootstrap}.</li>
 *   <li>{@code SET n += $props} is not supported — properties must be enumerated explicitly.</li>
 *   <li>{@code MERGE} on multi-property keys is more limited; prefer {@code MATCH/CREATE} idioms.</li>
 *   <li>Property maps are typed at the table level — no free-form key/value bags.</li>
 * </ul>
 */
public final class EmbeddedKuzu implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedKuzu.class);

    private final Path dbPath;
    private final Database database;
    private final Connection connection;
    /**
     * Prepared statements are cached by their Cypher text and reused across calls. KuzuDB's
     * {@code prepare} step parses and plans the query — when the ingest loop hits the same template
     * tens of thousands of times, amortising it gives a substantial speedup. The cache is bounded
     * implicitly by the number of distinct query templates the caller uses.
     */
    private final Map<String, PreparedStatement> stmtCache = new ConcurrentHashMap<>();

    /**
     * Buffer pool size — bytes of RAM Kuzu can use to cache pages. Default 1 GB; the
     * previous 512 MB starved bulk COPY on projects with ~10k+ nodes (the failing
     * scan was a {@code COPY Node FROM ... (PARALLEL=FALSE)} hitting "Buffer manager
     * exception: Unable to allocate memory! The buffer pool is full and no memory
     * could be freed!"). Override at the command line with
     * {@code -Dcvector.kuzu.bufferSizeMb=2048} or via the {@code CVECTOR_KUZU_BUFFER_MB}
     * env var if a very large project still hits the cap.
     */
    private static final long DEFAULT_BUFFER_SIZE = 1024L * 1024 * 1024;
    /** Max DB size on disk — 64 GB. Effectively a virtual address-space cap, not a real allocation. */
    private static final long DEFAULT_MAX_DB_SIZE = 64L * 1024 * 1024 * 1024;
    /**
     * Auto-checkpoint threshold in WAL bytes. Kuzu's default fires aggressively (~16 MB) which means
     * each batch transaction during a scan triggers a checkpoint + fsync. Bumping to 4 GB defers
     * checkpoints until {@link #close} on typical scans, cutting ingest wall-time dramatically on
     * Windows where fsync is expensive.
     */
    private static final long DEFAULT_CHECKPOINT_THRESHOLD = 4L * 1024 * 1024 * 1024;

    /**
     * Open Kuzu with the auto-resolved buffer pool size. Picks up overrides from
     * {@code -Dcvector.kuzu.bufferSizeMb}, {@code CVECTOR_KUZU_BUFFER_MB}, or auto-sizes
     * from system RAM. Use {@link #EmbeddedKuzu(Path, Long)} when the caller knows the
     * size explicitly (e.g. from {@code settings.json}).
     */
    public EmbeddedKuzu(Path dbPath) throws IOException {
        this(dbPath, null);
    }

    /**
     * Open Kuzu with an explicit buffer pool size (in bytes). Highest precedence among the
     * sizing knobs — when {@code bufferSizeBytes} is non-null and positive, it wins over
     * the {@code -D…} system property, the {@code CVECTOR_…} env var, and the RAM-based
     * auto-sized default. Used by {@code EmbeddedKuzuFactory} when the workspace's
     * {@code settings.json} pins {@code kuzu.bufferSizeMb} to a specific value.
     */
    public EmbeddedKuzu(Path dbPath, Long bufferSizeBytes) throws IOException {
        this.dbPath = dbPath;
        Files.createDirectories(dbPath.getParent());
        long bufferSize = (bufferSizeBytes != null && bufferSizeBytes > 0)
                ? bufferSizeBytes
                : resolveBufferSize();
        try {
            // Database(path, buffer_size, enableCompression, readOnly, maxDbSize, autoCheckpoint, checkpointThreshold).
            // autoCheckpoint=true is fine; the threshold is what controls how often we fsync.
            this.database = new Database(
                    dbPath.toString(),
                    bufferSize,
                    /* enableCompression */ true,
                    /* readOnly */ false,
                    DEFAULT_MAX_DB_SIZE,
                    /* autoCheckpoint */ true,
                    DEFAULT_CHECKPOINT_THRESHOLD);
            this.connection = new Connection(this.database);
            // Use all available cores. Kuzu's intra-query parallelism speeds up reads dramatically
            // and gives smaller gains on per-row writes (which serialize on the storage layer).
            try {
                this.connection.setMaxNumThreadForExec(Runtime.getRuntime().availableProcessors());
            } catch (RuntimeException ignored) { /* older Kuzu builds may not expose this */ }
            writeLockfile();
        } catch (RuntimeException e) {
            // Kuzu's open-time failure on a held file lock is opaque ("Could not set lock on
            // file"); enrich it with the PID + timestamp from a sibling .cvector-lock file
            // that we write at successful open. Helps users figure out which other process
            // owns the DB without grepping for `cvector` in their task list.
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Could not set lock")) {
                String diag = readLockfile(dbPath);
                throw new IOException("Kuzu database at " + dbPath + " is held by another cvector process"
                        + (diag != null ? " (" + diag + ")" : "")
                        + ". Stop the other process first, or set kuzu.sharedDb=false / isolated=true on this project to use its own DB.", e);
            }
            throw new IOException("failed to open Kuzu database at " + dbPath + ": " + e.getMessage(), e);
        }
        log.debug("opened Kuzu database at {}", dbPath);
    }

    /**
     * Drop a {@code .cvector-lock} sibling file with PID + timestamp so a future open
     * attempt that hits Kuzu's file lock can surface a useful "owned by PID X" error.
     * Best-effort: failure is logged but doesn't abort the open.
     */
    private void writeLockfile() {
        Path lock = dbPath.resolveSibling(".cvector-lock");
        try {
            String pid = String.valueOf(ProcessHandle.current().pid());
            String content = "pid=" + pid + "\ntimestamp=" + java.time.Instant.now() + "\n";
            Files.writeString(lock, content);
            // Best-effort cleanup on shutdown; if the JVM crashes the file leaks but the
            // next successful open overwrites it.
            lock.toFile().deleteOnExit();
        } catch (IOException ignored) { }
    }

    private static String readLockfile(Path dbPath) {
        Path lock = dbPath.resolveSibling(".cvector-lock");
        try {
            if (!Files.exists(lock)) return null;
            String content = Files.readString(lock).trim().replace("\n", ", ");
            return content;
        } catch (IOException ignored) { return null; }
    }

    /**
     * Decide the buffer-pool size for this Database instance. Kuzu's pool is fixed at open
     * (no live grow), so we have to commit to a number up front. Resolution order, highest
     * precedence first:
     * <ol>
     *   <li>{@code -Dcvector.kuzu.bufferSizeMb=<N>} system property — explicit override in MB.
     *       Use case: a CI machine where you know exactly how much RAM is budgeted.</li>
     *   <li>{@code CVECTOR_KUZU_BUFFER_MB=<N>} env var — same idea, friendlier for shell
     *       scripts and Docker entrypoints.</li>
     *   <li>Auto-sized at 25% of total system RAM, clamped to {@code [512 MB, 4 GB]}.
     *       25% gives Kuzu enough headroom for bulk COPY of a several-thousand-node project
     *       without starving the JVM heap, the parsers, or other apps on the host. The 4 GB
     *       upper cap protects against runaway allocation on workstation-class machines
     *       with 64+ GB of RAM where Kuzu doesn't actually need that much.</li>
     *   <li>{@link #DEFAULT_BUFFER_SIZE} (1 GB) when total RAM can't be queried — bare-metal
     *       JREs without {@code com.sun.management} or sandboxed JVMs.</li>
     * </ol>
     */
    private static long resolveBufferSize() {
        // Explicit override via system property.
        String sysProp = System.getProperty("cvector.kuzu.bufferSizeMb");
        Long fromSysProp = parseMb(sysProp);
        if (fromSysProp != null) {
            log.info("Kuzu buffer pool size: {} MB (from -Dcvector.kuzu.bufferSizeMb)", fromSysProp);
            return fromSysProp * 1024L * 1024L;
        }
        // Explicit override via environment.
        Long fromEnv = parseMb(System.getenv("CVECTOR_KUZU_BUFFER_MB"));
        if (fromEnv != null) {
            log.info("Kuzu buffer pool size: {} MB (from CVECTOR_KUZU_BUFFER_MB)", fromEnv);
            return fromEnv * 1024L * 1024L;
        }
        // Auto-size from system RAM. com.sun.management is in java.management which we
        // already addModules in the jpackage build, so this is available on the .exe too.
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long totalRam = sun.getTotalMemorySize();
                if (totalRam > 0) {
                    long target = totalRam / 4;                          // 25% of RAM
                    long min = 512L * 1024 * 1024;                       // never below 512 MB
                    long max = 4L * 1024 * 1024 * 1024;                  // never above 4 GB
                    long chosen = Math.max(min, Math.min(max, target));
                    log.info("Kuzu buffer pool size: {} MB (auto-sized from {} MB total RAM)",
                            chosen / (1024 * 1024), totalRam / (1024 * 1024));
                    return chosen;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to fixed default.
        }
        log.info("Kuzu buffer pool size: {} MB (default — could not detect system RAM)",
                DEFAULT_BUFFER_SIZE / (1024 * 1024));
        return DEFAULT_BUFFER_SIZE;
    }

    private static Long parseMb(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            long mb = Long.parseLong(raw.trim());
            return mb > 0 ? mb : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Translate the workspace's optional {@code kuzu.bufferSizeMb} setting into the byte
     * count the {@link #EmbeddedKuzu(Path, Long)} constructor wants. Returns {@code null}
     * (meaning "no override; auto-resolve from system property / env / RAM") when the
     * setting is absent or invalid. Lets callers fold this in one line:
     * {@snippet :
     *   EmbeddedKuzu kuzu = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg));
     * }
     */
    public static Long bufferSizeFromConfig(CvectorConfig cfg) {
        if (cfg == null || cfg.kuzu() == null) return null;
        Integer mb = cfg.kuzu().bufferSizeMb();
        if (mb == null || mb <= 0) return null;
        return mb.longValue() * 1024L * 1024L;
    }

    public static Path defaultDataRoot() {
        return Path.of(System.getProperty("user.home"), ".cvector", "kuzu-data");
    }

    /**
     * Path to a single shared Kuzu DB that holds every non-isolated project, partitioned
     * by the {@code projectId} node property. Introduced in 0.2.0; the previous
     * per-project directory layout is still reachable via {@link #isolatedDbPath}.
     */
    public static Path sharedDbPath() {
        return defaultDataRoot().resolve("graph.kuzu");
    }

    /** Pre-0.2.0 per-project DB path. Used when a project sets {@code isolated: true}. */
    public static Path isolatedDbPath(String projectId) {
        return defaultDataRoot().resolve(projectId).resolve("graph.kuzu");
    }

    /**
     * Resolves the on-disk Kuzu DB path for a project, honoring the workspace-level
     * {@code kuzu.sharedDb} flag and the per-project {@code isolated} override.
     *
     * <p>{@code cfg} may be {@code null} (very early bootstrap) — in that case we
     * fall back to the shared path so freshly-created projects land in the default
     * topology.
     */
    public static Path defaultDbPath(CvectorConfig cfg, String projectId) {
        if (cfg == null || cfg.isSharedDbMode(projectId)) return sharedDbPath();
        return isolatedDbPath(projectId);
    }

    /**
     * @deprecated Pass the loaded {@link CvectorConfig} so the shared/isolated routing
     *     can be honored; this overload always returns the pre-0.2.0 per-project path
     *     and is retained only for callers in early bootstrap paths.
     */
    @Deprecated
    public static Path defaultDbPath(String projectId) {
        return isolatedDbPath(projectId);
    }

    public Path dbPath() { return dbPath; }

    public boolean ping() {
        try (QueryResult r = connection.query("RETURN 1")) {
            return r.isSuccess();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs a Cypher statement with no parameters. Throws on failure. */
    public List<Map<String, Object>> read(String cypher) {
        return read(cypher, Map.of());
    }

    /**
     * Runs a parameterized Cypher statement and materialises every row as a {@code Map<String, Object>}
     * keyed by the result column names. Numeric/string/boolean values are unwrapped to Java types; nodes,
     * relationships, and structured values are returned as their Kuzu {@link Value#toString()} for now —
     * callers that need richer access can be extended later.
     */
    public List<Map<String, Object>> read(String cypher, Map<String, Object> params) {
        try (QueryResult result = run(cypher, params)) {
            if (!result.isSuccess()) {
                throw new RuntimeException("Kuzu query failed: " + result.getErrorMessage()
                        + "\nquery: " + cypher);
            }
            // Column names are stable per Cypher template (RETURN clause is fixed). Caching
            // saves a per-call JNI loop -- meaningful for the read hot path, which 50+ call
            // sites flow through. Validated against the live column count to fail loudly if
            // a query ever returns a different shape (it shouldn't, but the cost is one int compare).
            int cols = (int) result.getNumColumns();
            String[] names = colNamesCache.get(cypher);
            if (names == null || names.length != cols) {
                names = new String[cols];
                for (int i = 0; i < cols; i++) names[i] = result.getColumnName(i);
                colNamesCache.put(cypher, names);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                try (FlatTuple t = result.getNext()) {
                    // Pre-size LinkedHashMap with the exact column count so we don't pay for
                    // a rehash + table-double when {@code cols > 12}. Load factor 1.0 because
                    // we never grow.
                    Map<String, Object> row = new LinkedHashMap<>(cols, 1.0f);
                    for (int i = 0; i < cols; i++) {
                        try (Value v = t.getValue(i)) {
                            row.put(names[i], unwrap(v));
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    /** Runs a write Cypher statement and discards results. Throws on failure. */
    public void write(String cypher) { write(cypher, Map.of()); }

    public void write(String cypher, Map<String, Object> params) {
        try (QueryResult result = run(cypher, params)) {
            if (!result.isSuccess()) {
                throw new RuntimeException("Kuzu write failed: " + result.getErrorMessage()
                        + "\nquery: " + cypher);
            }
        }
    }

    /**
     * Pattern matching Cypher parameter references like {@code $foo}. Used to filter the caller's
     * params map down to only what the query references — Kuzu rejects {@code execute} when
     * extra parameters are bound that the prepared statement doesn't reference, even though Neo4j
     * silently ignores them. The match result is cached alongside the prepared statement so the
     * regex only runs once per unique Cypher template.
     */
    private static final java.util.regex.Pattern PARAM_REF = java.util.regex.Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    /** Cached parameter-name set per Cypher template — sibling of {@link #stmtCache}. */
    private final Map<String, java.util.Set<String>> paramRefCache = new ConcurrentHashMap<>();

    /**
     * Cached column-name array per Cypher template. Result columns are determined by the RETURN
     * clause, which is fixed per template, so the names string-equal across every call of the
     * same template. Caching saves a per-call JNI loop ({@code getColumnName(i)} ×N) on the read
     * hot path — meaningful since 50+ call sites in cvector funnel through {@link #read}.
     */
    private final Map<String, String[]> colNamesCache = new ConcurrentHashMap<>();

    private QueryResult run(String cypher, Map<String, Object> params) {
        // Always go through the prepared-statement cache, even when {@code params} is empty.
        // The previous branch did {@code connection.query(cypher)} on the empty path, which
        // forced Kuzu to re-parse + replan the Cypher every call. Caching the prepared
        // statement saves that work on repeated no-param queries (most of cvector's analytic
        // reads). The {@code stmtCache.computeIfAbsent} lookup is cheap on a ConcurrentHashMap.
        PreparedStatement stmt = stmtCache.computeIfAbsent(cypher, this::prepareOrThrow);
        boolean empty = params == null || params.isEmpty();
        if (empty) {
            return connection.execute(stmt, Map.of());
        }
        java.util.Set<String> referenced = paramRefCache.computeIfAbsent(cypher, EmbeddedKuzu::referencedParams);
        Map<String, Value> kuzuParams = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!referenced.contains(e.getKey())) continue;
                kuzuParams.put(e.getKey(), toValue(e.getValue()));
            }
            return connection.execute(stmt, kuzuParams);
        } finally {
            kuzuParams.values().forEach(Value::close);
        }
    }

    private static java.util.Set<String> referencedParams(String cypher) {
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = PARAM_REF.matcher(cypher);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private PreparedStatement prepareOrThrow(String cypher) {
        PreparedStatement stmt = connection.prepare(cypher);
        if (!stmt.isSuccess()) {
            String err = stmt.getErrorMessage();
            try { stmt.close(); } catch (RuntimeException ignored) { }
            throw new RuntimeException("Kuzu prepare failed: " + err + "\nquery: " + cypher);
        }
        return stmt;
    }

    @SuppressWarnings("unchecked")
    private static Value toValue(Object o) {
        if (o == null) return Value.createNull();
        if (o instanceof Value v) return v;
        if (o instanceof Boolean b) return new Value(b);
        if (o instanceof Integer i) return new Value(i);
        if (o instanceof Long l) return new Value(l);
        if (o instanceof Float f) return new Value(f);
        if (o instanceof Double d) return new Value(d);
        if (o instanceof String s) return new Value(s);
        // Fall back to string serialisation for unsupported types. Kuzu does support typed lists,
        // structs, and maps via dedicated constructors but the generic <T> Value(T) handles primitives.
        return new Value(o.toString());
    }

    private static Object unwrap(Value v) {
        if (v == null || v.isNull()) return null;
        // LIST/ARRAY values: Kuzu's typed getValue() throws (the templated <T> can't infer
        // a container type), and toString() produces a single string like "[a, b, c]" that
        // serialises poorly over JSON. Parse the toString form into a real Java list so
        // controllers can return it as a JSON array.
        DataTypeID kind = null;
        try { kind = v.getDataType().getID(); } catch (RuntimeException ignored) { }
        if (kind == DataTypeID.LIST || kind == DataTypeID.ARRAY) {
            return parseKuzuListString(v.toString());
        }
        try {
            return v.getValue();
        } catch (RuntimeException e) {
            // Other composite types (nodes, rels, structs, maps) fall back to their toString().
            return v.toString();
        }
    }

    /**
     * Parse Kuzu's list toString form — {@code [a,b,c]} with no quoting around elements — into
     * a {@code List<String>}. Whitespace after the comma is tolerated since formats vary across
     * Kuzu releases. Returns an empty list for the literal {@code []}.
     */
    private static List<String> parseKuzuListString(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') {
            return List.of();
        }
        String inner = s.substring(1, s.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        String[] parts = inner.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(p.trim());
        return out;
    }

    @Override
    public void close() {
        for (PreparedStatement s : stmtCache.values()) {
            try { s.close(); } catch (RuntimeException ignored) { }
        }
        stmtCache.clear();
        try { connection.close(); } catch (RuntimeException ignored) { }
        try { database.close(); } catch (RuntimeException ignored) { }
    }
}
