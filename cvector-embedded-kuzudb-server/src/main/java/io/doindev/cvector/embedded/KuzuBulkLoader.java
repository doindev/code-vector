package io.doindev.cvector.embedded;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.store.GraphIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk ingest path for the embedded backend. Buffers events in memory, then stages typed CSVs
 * and runs {@code COPY <table> FROM '...'} per Node table + each populated REL table.
 *
 * <p>Trade-offs vs {@link KuzuIngestor}:
 * <ul>
 *   <li><b>Speed:</b> ~10-100× faster than per-row MERGE on Windows. Kuzu's COPY path is the
 *       same code path it uses for bulk loading Parquet/Arrow datasets.</li>
 *   <li><b>Constraint:</b> COPY appends — it doesn't merge. Only safe to use when the target
 *       tables are empty (initial scan or post-wipe). {@code ScanCommand} checks the Node row
 *       count and picks the loader path accordingly.</li>
 *   <li><b>Dedup still happens:</b> we collapse {@code NodeUpsert}s by id and {@code EdgeUpsert}s
 *       by (from, type, to) in memory so the staged CSV has one row per distinct entity.</li>
 * </ul>
 */
public final class KuzuBulkLoader implements GraphIngestor {

    private static final Logger log = LoggerFactory.getLogger(KuzuBulkLoader.class);

    /** REL-table column order: must match {@code KuzuSchemaBootstrap.bootstrap}'s CREATE REL TABLE statement. */
    private static final List<String> EDGE_COLUMN_ORDER = List.of(
            "confidence", "callSiteLine", "kind", "via", "viaMethodReference", "ambiguous"
    );

    private final EmbeddedKuzu kuzu;
    private final String scanStartIso;
    private final List<String> nodeColumnOrder;
    private final Map<String, BufferedNode> nodeBuffer = new LinkedHashMap<>();
    private final Map<EdgeKey, Map<String, Object>> edgeBuffer = new LinkedHashMap<>();
    private int totalNodes;
    private int totalEdges;

    private record EdgeKey(String fromId, String type, String toId) {}

    private static final class BufferedNode {
        NodeKey key;
        final Map<String, Object> props = new HashMap<>();
        BufferedNode(NodeKey key) { this.key = key; }
    }

    public KuzuBulkLoader(EmbeddedKuzu kuzu) {
        this(kuzu, Instant.now());
    }

    public KuzuBulkLoader(EmbeddedKuzu kuzu, Instant scanStart) {
        this.kuzu = kuzu;
        this.scanStartIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(scanStart.atOffset(ZoneOffset.UTC));
        this.nodeColumnOrder = fetchNodeColumns(kuzu);
    }

    private static List<String> fetchNodeColumns(EmbeddedKuzu kuzu) {
        List<Map<String, Object>> rows = kuzu.read("CALL TABLE_INFO('Node') RETURN name");
        List<String> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(String.valueOf(r.get("name")));
        return out;
    }

    @Override
    public synchronized void accept(GraphEvent event) {
        if (event instanceof GraphEvent.NodeUpsert n) {
            BufferedNode buf = nodeBuffer.computeIfAbsent(n.key().id(), id -> new BufferedNode(n.key()));
            buf.key = n.key();
            if (n.props() != null) buf.props.putAll(n.props());
        } else if (event instanceof GraphEvent.EdgeUpsert e) {
            EdgeKey k = new EdgeKey(e.from().id(), e.type(), e.to().id());
            Map<String, Object> merged = edgeBuffer.computeIfAbsent(k, x -> new HashMap<>());
            if (e.props() != null) merged.putAll(e.props());
        } else if (event instanceof GraphEvent.NodeRemove r) {
            // Bulk loader is only used for empty-DB initial ingest. Removes shouldn't occur here.
            nodeBuffer.remove(r.key().id());
        }
    }

    @Override
    public synchronized void flush() {
        if (nodeBuffer.isEmpty() && edgeBuffer.isEmpty()) return;
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("cvector-kuzu-bulk-");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to allocate bulk-loader staging dir", e);
        }
        try {
            copyNodes(tmpDir);
            copyEdges(tmpDir);
        } finally {
            deleteTree(tmpDir);
        }
    }

    private void copyNodes(Path tmpDir) {
        if (nodeBuffer.isEmpty()) return;
        Path csv = tmpDir.resolve("nodes.csv");
        try (BufferedWriter w = Files.newBufferedWriter(csv)) {
            for (BufferedNode buf : nodeBuffer.values()) {
                writeNodeRow(w, buf);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing nodes.csv", e);
        }
        kuzu.write("COPY Node FROM '" + csv.toString().replace('\\', '/') + "'");
        totalNodes += nodeBuffer.size();
        nodeBuffer.clear();
    }

    private void copyEdges(Path tmpDir) {
        if (edgeBuffer.isEmpty()) return;
        // Group by relationship type so each REL table gets its own COPY.
        Map<String, List<Map.Entry<EdgeKey, Map<String, Object>>>> byType = new LinkedHashMap<>();
        for (Map.Entry<EdgeKey, Map<String, Object>> e : edgeBuffer.entrySet()) {
            byType.computeIfAbsent(e.getKey().type(), t -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Map.Entry<EdgeKey, Map<String, Object>>>> bucket : byType.entrySet()) {
            String type = bucket.getKey();
            Path csv = tmpDir.resolve("edges-" + type + ".csv");
            try (BufferedWriter w = Files.newBufferedWriter(csv)) {
                for (Map.Entry<EdgeKey, Map<String, Object>> e : bucket.getValue()) {
                    writeEdgeRow(w, e.getKey(), e.getValue());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed writing edges-" + type + ".csv", e);
            }
            try {
                kuzu.write("COPY " + type + " FROM '" + csv.toString().replace('\\', '/') + "'");
            } catch (RuntimeException ex) {
                // An edge whose endpoint isn't in the Node table after the COPY (rare — only
                // happens if a parser emits an edge to/from a node it never declared) makes the
                // whole COPY fail. Log and skip the rest of the type rather than aborting the scan.
                log.warn("COPY {} failed: {}", type, ex.getMessage());
            }
            totalEdges += bucket.getValue().size();
        }
        edgeBuffer.clear();
    }

    private void writeNodeRow(BufferedWriter w, BufferedNode buf) throws IOException {
        Map<String, Object> raw = buf.props;
        NodeKey key = buf.key;
        String contentHash = KuzuNodeHash.compute(key, raw);
        for (int i = 0; i < nodeColumnOrder.size(); i++) {
            if (i > 0) w.write(',');
            String col = nodeColumnOrder.get(i);
            String cell = switch (col) {
                case "id" -> key.id();
                case "projectId" -> key.projectId();
                case "label" -> key.label();
                case "fqName" -> key.fqName();
                case "lastIngestedAt" -> scanStartIso;
                case "contentHash" -> contentHash;
                default -> formatNodeField(col, raw.get(col));
            };
            if (cell != null) w.write(escapeCsv(cell));
        }
        w.write('\n');
    }

    private static String formatNodeField(String col, Object v) {
        if (v == null) return null;
        if (KuzuIngestor.BOOL_NODE_PROPS.contains(col)) {
            if (v instanceof Boolean b) return b.toString();
            return Boolean.parseBoolean(v.toString()) ? "true" : "false";
        }
        if (KuzuIngestor.INT64_NODE_PROPS.contains(col)) {
            if (v instanceof Number n) return Long.toString(n.longValue());
            try { return Long.toString(Long.parseLong(v.toString())); }
            catch (NumberFormatException e) { return null; }
        }
        return v.toString();
    }

    private void writeEdgeRow(BufferedWriter w, EdgeKey key, Map<String, Object> raw) throws IOException {
        w.write(escapeCsv(key.fromId()));
        w.write(',');
        w.write(escapeCsv(key.toId()));
        for (String prop : EDGE_COLUMN_ORDER) {
            w.write(',');
            String cell = formatEdgeField(prop, raw.get(prop));
            if (cell != null) w.write(escapeCsv(cell));
        }
        w.write('\n');
    }

    private static String formatEdgeField(String col, Object v) {
        if (v == null) return null;
        if (KuzuIngestor.BOOL_EDGE_PROPS.contains(col)) {
            if (v instanceof Boolean b) return b.toString();
            return Boolean.parseBoolean(v.toString()) ? "true" : "false";
        }
        if (KuzuIngestor.INT64_EDGE_PROPS.contains(col)) {
            if (v instanceof Number n) return Long.toString(n.longValue());
            try { return Long.toString(Long.parseLong(v.toString())); }
            catch (NumberFormatException e) { return null; }
        }
        if (KuzuIngestor.DOUBLE_EDGE_PROPS.contains(col)) {
            if (v instanceof Number n) return Double.toString(n.doubleValue());
            try { return Double.toString(Double.parseDouble(v.toString())); }
            catch (NumberFormatException e) { return null; }
        }
        return v.toString();
    }

    /** Standard CSV escape: quote if the field contains a comma, quote, or newline. */
    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean needs = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') { needs = true; break; }
        }
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    @Override
    public int totalNodes() { return totalNodes; }

    @Override
    public int totalEdges() { return totalEdges; }

    @Override
    public void close() { flush(); }

    public String scanStartIso() { return scanStartIso; }
}
