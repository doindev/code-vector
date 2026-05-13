package io.doindev.cvector.embedded;

import com.kuzudb.Connection;
import com.kuzudb.Database;
import com.kuzudb.DataTypeID;
import com.kuzudb.FlatTuple;
import com.kuzudb.PreparedStatement;
import com.kuzudb.QueryResult;
import com.kuzudb.Value;
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

    /** Default buffer pool size — 512 MB. Kuzu's default is much smaller and starves ingest. */
    private static final long DEFAULT_BUFFER_SIZE = 512L * 1024 * 1024;
    /** Max DB size on disk — 64 GB. Effectively a virtual address-space cap, not a real allocation. */
    private static final long DEFAULT_MAX_DB_SIZE = 64L * 1024 * 1024 * 1024;
    /**
     * Auto-checkpoint threshold in WAL bytes. Kuzu's default fires aggressively (~16 MB) which means
     * each batch transaction during a scan triggers a checkpoint + fsync. Bumping to 4 GB defers
     * checkpoints until {@link #close} on typical scans, cutting ingest wall-time dramatically on
     * Windows where fsync is expensive.
     */
    private static final long DEFAULT_CHECKPOINT_THRESHOLD = 4L * 1024 * 1024 * 1024;

    public EmbeddedKuzu(Path dbPath) throws IOException {
        this.dbPath = dbPath;
        Files.createDirectories(dbPath.getParent());
        try {
            // Database(path, buffer_size, enableCompression, readOnly, maxDbSize, autoCheckpoint, checkpointThreshold).
            // autoCheckpoint=true is fine; the threshold is what controls how often we fsync.
            this.database = new Database(
                    dbPath.toString(),
                    DEFAULT_BUFFER_SIZE,
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
        } catch (RuntimeException e) {
            throw new IOException("failed to open Kuzu database at " + dbPath + ": " + e.getMessage(), e);
        }
        log.debug("opened Kuzu database at {}", dbPath);
    }

    public static Path defaultDataRoot() {
        return Path.of(System.getProperty("user.home"), ".cvector", "kuzu-data");
    }

    public static Path defaultDbPath(String projectId) {
        return defaultDataRoot().resolve(projectId).resolve("graph.kuzu");
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
            long cols = result.getNumColumns();
            String[] names = new String[(int) cols];
            for (long i = 0; i < cols; i++) names[(int) i] = result.getColumnName(i);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                try (FlatTuple t = result.getNext()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (long i = 0; i < cols; i++) {
                        try (Value v = t.getValue(i)) {
                            row.put(names[(int) i], unwrap(v));
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

    private QueryResult run(String cypher, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return connection.query(cypher);
        }
        PreparedStatement stmt = stmtCache.computeIfAbsent(cypher, this::prepareOrThrow);
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
