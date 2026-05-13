package io.doindev.cvector.embedded;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.store.GraphIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@link GraphEvent}s into a Kuzu database. Kuzu's Cypher dialect doesn't support
 * {@code SET n += $props} or {@code UNWIND $rows AS row} with list-of-maps parameters, so we
 * issue one parameterised MERGE/SET per row. To get the round-trip count down we deduplicate
 * upserts in memory before writing: per scan, parsers emit the same node id from multiple
 * sites (e.g. a class is emitted from its declaration AND from each contained method as
 * {@code classId}). Each NodeUpsert merges its props into a single buffered map per id, and
 * each EdgeUpsert merges into a single buffered map per (from, type, to). At flush time we
 * issue one MERGE/SET per distinct entity.
 *
 * <p>The Cypher template for each row is built from the set of properties actually present,
 * iterated in the fixed order of {@link #NODE_PROPERTY_ORDER} (or {@link #EDGE_PROPERTY_ORDER}).
 * Rows with the same property shape produce identical Cypher, which hits the
 * {@link EmbeddedKuzu} prepared-statement cache — in practice the scan converges on a few
 * dozen shapes and the prepare cost is amortised. Because we only SET the columns the parser
 * emitted, a later partial upsert of the same node id leaves untouched columns intact,
 * matching Neo4j's {@code SET n += $row} semantics without a coalesce per column.
 *
 * <p>The schema is a single polymorphic {@code Node} table with a fixed set of property columns
 * ({@link KuzuSchemaBootstrap}). Properties emitted by parsers that aren't in the schema get
 * silently dropped — Kuzu would otherwise reject the write entirely. The full set of expected
 * properties is in {@link #NODE_PROPERTY_ORDER}.
 */
public class KuzuIngestor implements GraphIngestor {

    private static final Logger log = LoggerFactory.getLogger(KuzuIngestor.class);
    /**
     * Rows per Kuzu transaction. Smaller chunks bound rollback blast-radius if a write fails;
     * empirically larger chunks don't speed up Windows ingest because the per-MERGE cost
     * (~5 ms) dominates over commit/checkpoint overhead.
     */
    private static final int FLUSH_CHUNK = 500;

    /**
     * Properties defined on the Node table, in a deterministic order so the generated Cypher
     * template is stable. The first four are derived from the NodeKey on every row; the rest are
     * optional schema columns.
     */
    static final List<String> NODE_PROPERTY_ORDER = List.of(
            "id", "projectId", "label", "fqName", "name", "kind", "path", "language",
            "startLine", "endLine", "lineCount", "fileId", "classId", "tableId", "contractId",
            "signature", "returnType", "paramCount", "receiver", "type",
            "visibility", "isStatic", "isAbstract", "isTest", "isAsync",
            "isConstructor", "isEntry", "isQueueListener", "isScheduled",
            "isExternal", "isBase", "isAnonymousHandler",
            "isClassmethod", "isDataclass", "isFinal", "isFixture", "isProperty",
            "cssModule", "moduleExport",
            "decorators", "package", "namespace",
            "value", "defaultValue", "valueType",
            "httpMethod", "httpPath", "framework", "viewRef",
            "scope", "source", "resourceType", "provider",
            "groupId", "artifactId", "version", "versionSource",
            "repository", "tag", "digest", "baseImage",
            "port", "protocol", "command", "rootPath",
            "lastScanCommit", "contentHash", "fileContentHash"
    );
    /** Lookup set derived from {@link #NODE_PROPERTY_ORDER}. */
    static final Set<String> NODE_PROPERTY_NAMES = Set.copyOf(NODE_PROPERTY_ORDER);

    static final Set<String> INT64_NODE_PROPS = Set.of(
            "startLine", "endLine", "lineCount", "paramCount", "port"
    );
    static final Set<String> BOOL_NODE_PROPS = Set.of(
            "isStatic", "isAbstract", "isTest", "isAsync", "isConstructor",
            "isEntry", "isQueueListener", "isScheduled", "isExternal", "isBase",
            "isAnonymousHandler",
            "isClassmethod", "isDataclass", "isFinal", "isFixture", "isProperty",
            "cssModule", "moduleExport"
    );

    static final List<String> EDGE_PROPERTY_ORDER = List.of(
            "confidence", "callSiteLine", "kind", "via", "viaMethodReference", "ambiguous"
    );
    static final Set<String> EDGE_PROPERTY_NAMES = Set.copyOf(EDGE_PROPERTY_ORDER);
    static final Set<String> INT64_EDGE_PROPS = Set.of("callSiteLine");
    static final Set<String> DOUBLE_EDGE_PROPS = Set.of("confidence");
    static final Set<String> BOOL_EDGE_PROPS = Set.of("viaMethodReference", "ambiguous");

    /** Identity primary-key columns that are always written. */
    private static final Set<String> NODE_KEY_COLUMNS = Set.of("id", "projectId", "label", "fqName");

    /**
     * Per-shape Cypher template caches. Keys are the property names in canonical order. Capped
     * only by the number of distinct shapes emitted — the parsers settle on a few dozen shapes
     * total in practice.
     */
    private final Map<List<String>, String> nodeCypherCache = new HashMap<>();
    private final Map<EdgeShape, String> edgeCypherCache = new HashMap<>();

    private record EdgeShape(String type, List<String> props) {}
    private record EdgeKey(String fromId, String type, String toId) {}

    private final EmbeddedKuzu kuzu;
    /** Scan start, stamped onto every node write as {@code lastIngestedAt}. Skipped (unchanged) rows keep their old timestamp, which gives {@code cv_changes} semantically-correct "what actually changed" output. */
    private final String scanStartIso;
    /** Buffered nodes keyed by id; values carry the latest key + merged props. */
    private final Map<String, BufferedNode> nodeBuffer = new LinkedHashMap<>();
    /** Buffered edges keyed by (from,type,to); values are merged props. */
    private final Map<EdgeKey, Map<String, Object>> edgeBuffer = new LinkedHashMap<>();
    /**
     * Every node id we saw during the scan (written + skipped). Drives {@code cleanupStale}'s
     * staleness check: any managed-label row whose id is <em>not</em> in this set is stale and
     * gets removed. Replaces the prior lastIngestedAt-based scheme which required bulk-bumping
     * timestamps on every skipped row.
     */
    private final java.util.Set<String> touchedNodeIds = new java.util.HashSet<>();
    private int totalNodes;
    private int totalEdges;

    private static final class BufferedNode {
        NodeKey key;
        final Map<String, Object> props = new HashMap<>();
        /** Filled at flush time by {@link KuzuNodeHash}. Stored alongside the row so re-scans can skip unchanged data. */
        String contentHash;

        BufferedNode(NodeKey key) { this.key = key; }
    }

    public KuzuIngestor(EmbeddedKuzu kuzu) {
        this(kuzu, Instant.now());
    }

    public KuzuIngestor(EmbeddedKuzu kuzu, Instant scanStart) {
        this.kuzu = kuzu;
        // Kuzu's TIMESTAMP literal parser accepts ISO-8601 like "2025-05-12T00:00:00".
        this.scanStartIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(scanStart.atOffset(ZoneOffset.UTC));
    }

    public String scanStartIso() { return scanStartIso; }

    @Override
    public synchronized void accept(GraphEvent event) {
        if (event instanceof GraphEvent.NodeUpsert n) {
            BufferedNode buf = nodeBuffer.computeIfAbsent(n.key().id(), id -> new BufferedNode(n.key()));
            buf.key = n.key();
            // last-write-wins on individual property keys, but absent props don't clobber
            if (n.props() != null) buf.props.putAll(n.props());
            touchedNodeIds.add(n.key().id());
        } else if (event instanceof GraphEvent.EdgeUpsert e) {
            EdgeKey k = new EdgeKey(e.from().id(), e.type(), e.to().id());
            Map<String, Object> merged = edgeBuffer.computeIfAbsent(k, x -> new HashMap<>());
            if (e.props() != null) merged.putAll(e.props());
        } else if (event instanceof GraphEvent.NodeRemove r) {
            // Drop any buffered upsert for this id first so the delete isn't immediately re-created
            // by a stale flush. Edges and nodes share a single table, so DETACH DELETE fans out.
            nodeBuffer.remove(r.key().id());
            kuzu.write("MATCH (n:Node {id: $id}) DETACH DELETE n", Map.of("id", r.key().id()));
        }
    }

    private void flushNodes() {
        if (nodeBuffer.isEmpty()) return;
        List<BufferedNode> all = new ArrayList<>(nodeBuffer.values());
        nodeBuffer.clear();

        // Skip-unchanged optimisation: every buffered node carries a deterministic content hash
        // (excluding lastIngestedAt). Pre-fetch existing (id, contentHash) pairs for every buffered
        // id; if the existing hash matches the new one, the node hasn't changed and we skip the
        // MERGE entirely. For a re-scan on stable source this drops ~14k writes to ~0.
        Map<String, String> existingHashes = fetchExistingHashes(all);
        List<BufferedNode> toWrite = new ArrayList<>(all.size());
        List<String> skippedIds = new ArrayList<>(all.size());
        for (BufferedNode b : all) {
            b.contentHash = KuzuNodeHash.compute(b.key, b.props);
            String existing = existingHashes.get(b.key.id());
            if (existing != null && existing.equals(b.contentHash)) {
                skippedIds.add(b.key.id());
                continue;
            }
            toWrite.add(b);
        }
        totalNodes += all.size();  // surface to the user includes skipped (they're "ingested" logically)
        log.debug("flushNodes: total={} skipped={} writing={}", all.size(), skippedIds.size(), toWrite.size());

        if (toWrite.isEmpty()) return;
        for (int from = 0; from < toWrite.size(); from += FLUSH_CHUNK) {
            int to = Math.min(from + FLUSH_CHUNK, toWrite.size());
            List<BufferedNode> chunk = toWrite.subList(from, to);
            runInTransaction(() -> {
                for (BufferedNode b : chunk) writeNode(b);
            });
        }
    }

    /**
     * Snapshot of every node id this ingestor saw during the scan (written + skipped). Drives
     * {@link KuzuPostScan#cleanupStale} — anything in the managed-label set that's <em>not</em>
     * in this set is stale.
     */
    public synchronized java.util.Set<String> touchedNodeIds() {
        return java.util.Set.copyOf(touchedNodeIds);
    }

    /**
     * One bulk lookup of {@code (id, contentHash)} for every buffered node id, inlined as a Cypher
     * list literal. Inlining avoids the list-of-strings parameter-binding limitation in Kuzu's
     * Java client; for typical projects the literal is well under 1 MB.
     */
    private Map<String, String> fetchExistingHashes(List<BufferedNode> all) {
        if (all.isEmpty()) return Map.of();
        StringBuilder ids = new StringBuilder(all.size() * 18);
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) ids.append(", ");
            String id = all.get(i).key.id();
            ids.append('\'').append(id.replace("'", "\\'")).append('\'');
        }
        Map<String, String> out = new HashMap<>(all.size() * 2);
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (n:Node) WHERE n.id IN [" + ids + "] "
                            + "RETURN n.id AS id, n.contentHash AS hash");
            for (Map<String, Object> r : rows) {
                Object id = r.get("id");
                Object hash = r.get("hash");
                if (id != null && hash != null) out.put(id.toString(), hash.toString());
            }
        } catch (RuntimeException e) {
            // Treat lookup failures as "nothing exists" — we'll fall back to writing everything.
            log.debug("contentHash pre-fetch failed, will write all rows: {}", e.getMessage());
        }
        return out;
    }

    private void flushEdges() {
        if (edgeBuffer.isEmpty()) return;
        List<Map.Entry<EdgeKey, Map<String, Object>>> all = new ArrayList<>(edgeBuffer.entrySet());
        edgeBuffer.clear();

        // Skip-unchanged for edges: edges have no content hash, but they're keyed on (from, type, to)
        // and rarely change properties once created. Pre-fetch the set of existing (from, to) pairs
        // per REL type and skip any edge whose pair already exists. Reduces ~10 k re-scan MERGEs
        // to ~0 on a stable graph.
        Map<String, java.util.Set<String>> existingPerType = fetchExistingEdgePairs(all);
        List<Map.Entry<EdgeKey, Map<String, Object>>> toWrite = new ArrayList<>(all.size());
        int skipped = 0;
        for (Map.Entry<EdgeKey, Map<String, Object>> e : all) {
            EdgeKey k = e.getKey();
            java.util.Set<String> pairs = existingPerType.get(k.type());
            if (pairs != null && pairs.contains(k.fromId() + "|" + k.toId())) {
                skipped++;
                continue;
            }
            toWrite.add(e);
        }
        totalEdges += all.size();
        log.debug("flushEdges: total={} skipped={} writing={}", all.size(), skipped, toWrite.size());

        if (toWrite.isEmpty()) return;
        for (int from = 0; from < toWrite.size(); from += FLUSH_CHUNK) {
            int to = Math.min(from + FLUSH_CHUNK, toWrite.size());
            List<Map.Entry<EdgeKey, Map<String, Object>>> chunk = toWrite.subList(from, to);
            runInTransaction(() -> {
                for (Map.Entry<EdgeKey, Map<String, Object>> e : chunk) writeEdge(e.getKey(), e.getValue());
            });
        }
    }

    /**
     * Per REL type, query all (from, to) pairs currently in the table. We only need to know which
     * pairs already exist — property updates on existing edges are silently dropped, which matches
     * the typical re-scan case where (from, type, to) is stable.
     */
    private Map<String, java.util.Set<String>> fetchExistingEdgePairs(
            List<Map.Entry<EdgeKey, Map<String, Object>>> all) {
        java.util.Set<String> typesNeeded = new java.util.HashSet<>();
        for (Map.Entry<EdgeKey, Map<String, Object>> e : all) typesNeeded.add(e.getKey().type());
        Map<String, java.util.Set<String>> out = new HashMap<>();
        for (String type : typesNeeded) {
            java.util.Set<String> pairs = new java.util.HashSet<>();
            try {
                List<Map<String, Object>> rows = kuzu.read(
                        "MATCH (a:Node)-[r:" + type + "]->(b:Node) RETURN a.id AS src, b.id AS dst");
                for (Map<String, Object> r : rows) {
                    Object src = r.get("src");
                    Object dst = r.get("dst");
                    if (src != null && dst != null) pairs.add(src + "|" + dst);
                }
            } catch (RuntimeException ex) {
                log.debug("existing-edge fetch failed for {}: {}", type, ex.getMessage());
            }
            out.put(type, pairs);
        }
        return out;
    }

    private void runInTransaction(Runnable body) {
        kuzu.write("BEGIN TRANSACTION", Map.of());
        try {
            body.run();
            kuzu.write("COMMIT", Map.of());
        } catch (RuntimeException e) {
            try { kuzu.write("ROLLBACK", Map.of()); }
            catch (RuntimeException ignored) { /* roll-forward */ }
            throw e;
        }
    }

    private void writeNode(BufferedNode buf) {
        NodeKey key = buf.key;
        Map<String, Object> raw = buf.props;
        List<String> shape = new ArrayList<>(8);
        Map<String, Object> params = new LinkedHashMap<>(8 + NODE_KEY_COLUMNS.size());
        params.put("id", key.id());
        params.put("projectId", key.projectId());
        params.put("label", key.label());
        params.put("fqName", key.fqName());
        params.put("lastIngestedAt", scanStartIso);
        params.put("contentHash", buf.contentHash != null ? buf.contentHash : KuzuNodeHash.compute(key, raw));
        for (String prop : NODE_PROPERTY_ORDER) {
            if (NODE_KEY_COLUMNS.contains(prop)) continue;
            Object v = raw.get(prop);
            if (v == null) continue;
            Object coerced = coerceNodeValue(prop, v);
            if (coerced == null) continue;
            shape.add(prop);
            params.put(prop, coerced);
        }
        String cypher = nodeCypherCache.computeIfAbsent(shape, KuzuIngestor::buildNodeCypher);
        kuzu.write(cypher, params);
    }

    private void writeEdge(EdgeKey edgeKey, Map<String, Object> raw) {
        List<String> shape = new ArrayList<>(EDGE_PROPERTY_ORDER.size());
        Map<String, Object> params = new LinkedHashMap<>(2 + EDGE_PROPERTY_ORDER.size());
        params.put("fromId", edgeKey.fromId());
        params.put("toId", edgeKey.toId());
        for (String prop : EDGE_PROPERTY_ORDER) {
            Object v = raw.get(prop);
            if (v == null) continue;
            Object coerced = coerceEdgeValue(prop, v);
            if (coerced == null) continue;
            shape.add(prop);
            params.put(prop, coerced);
        }
        EdgeShape key = new EdgeShape(edgeKey.type(), shape);
        String cypher = edgeCypherCache.computeIfAbsent(key, KuzuIngestor::buildEdgeCypher);
        kuzu.write(cypher, params);
    }

    private static String buildNodeCypher(List<String> shape) {
        StringBuilder sb = new StringBuilder("MERGE (n:Node {id: $id}) SET ");
        sb.append("n.projectId = $projectId, n.label = $label, n.fqName = $fqName");
        sb.append(", n.lastIngestedAt = timestamp($lastIngestedAt)");
        sb.append(", n.contentHash = $contentHash");
        for (String prop : shape) {
            sb.append(", n.").append(prop).append(" = $").append(prop);
        }
        return sb.toString();
    }

    private static String buildEdgeCypher(EdgeShape key) {
        StringBuilder sb = new StringBuilder("MATCH (a:Node {id: $fromId}), (b:Node {id: $toId}) ");
        sb.append("MERGE (a)-[r:").append(key.type()).append("]->(b)");
        if (!key.props().isEmpty()) {
            sb.append(" SET ");
            for (int i = 0; i < key.props().size(); i++) {
                if (i > 0) sb.append(", ");
                String prop = key.props().get(i);
                sb.append("r.").append(prop).append(" = $").append(prop);
            }
        }
        return sb.toString();
    }

    private static Object coerceNodeValue(String prop, Object v) {
        if (v == null) return null;
        if (BOOL_NODE_PROPS.contains(prop)) {
            if (v instanceof Boolean) return v;
            return Boolean.parseBoolean(v.toString());
        }
        if (INT64_NODE_PROPS.contains(prop)) {
            if (v instanceof Number n) return n.longValue();
            try { return Long.parseLong(v.toString()); }
            catch (NumberFormatException e) { return null; }
        }
        return v.toString();
    }

    private static Object coerceEdgeValue(String prop, Object v) {
        if (v == null) return null;
        if (BOOL_EDGE_PROPS.contains(prop)) {
            if (v instanceof Boolean) return v;
            return Boolean.parseBoolean(v.toString());
        }
        if (INT64_EDGE_PROPS.contains(prop)) {
            if (v instanceof Number n) return n.longValue();
            try { return Long.parseLong(v.toString()); }
            catch (NumberFormatException e) { return null; }
        }
        if (DOUBLE_EDGE_PROPS.contains(prop)) {
            if (v instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(v.toString()); }
            catch (NumberFormatException e) { return null; }
        }
        return v.toString();
    }

    public synchronized void flush() {
        flushNodes();
        flushEdges();
    }

    public int totalNodes() { return totalNodes; }
    public int totalEdges() { return totalEdges; }

    @Override
    public void close() {
        flush();
    }
}
