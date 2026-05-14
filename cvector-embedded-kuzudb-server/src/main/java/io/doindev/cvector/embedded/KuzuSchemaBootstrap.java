package io.doindev.cvector.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Idempotently declares cvector's schema in a Kuzu database. Kuzu requires upfront schema (unlike
 * Neo4j's schema-on-read), so this is the single source of truth.
 *
 * <p>We use a single polymorphic {@code Node} table discriminated by a {@code label} property,
 * mirroring Neo4j's flexible label model. Each relationship type is its own {@code REL TABLE} on
 * {@code Node → Node}. The trade-off: we give up Kuzu's per-table columnar storage benefit, but
 * gain compatibility with cvector's existing parsers that emit arbitrary node labels.
 *
 * <p>{@link #bootstrap} runs the {@code CREATE NODE TABLE IF NOT EXISTS} DDL first, then issues
 * an {@code ALTER TABLE ... ADD ... IF NOT EXISTS} per column. The ADD pass is what brings older
 * databases up to the current schema after we declare new parser-emitted properties — Kuzu's
 * {@code CREATE NODE TABLE IF NOT EXISTS} is a no-op when the table already exists, so without
 * the ADD pass a schema-evolved cvector build would silently drop the new properties on every
 * write into a pre-existing database.
 */
public final class KuzuSchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KuzuSchemaBootstrap.class);

    /**
     * Column → Kuzu type. Drives both the initial CREATE TABLE and the subsequent ADD-IF-MISSING
     * pass. Mirror this against {@link KuzuIngestor#NODE_PROPERTY_ORDER}; anything declared there
     * but missing here gets silently dropped on every write.
     */
    private static final Map<String, String> NODE_COLUMNS = nodeColumns();

    private static Map<String, String> nodeColumns() {
        // LinkedHashMap to keep CREATE TABLE in the same order as NODE_PROPERTY_ORDER for diffing.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "STRING");
        m.put("projectId", "STRING");
        m.put("label", "STRING");
        m.put("fqName", "STRING");
        m.put("name", "STRING");
        m.put("kind", "STRING");
        m.put("path", "STRING");
        m.put("language", "STRING");
        m.put("startLine", "INT64");
        m.put("endLine", "INT64");
        m.put("lineCount", "INT64");
        m.put("fileId", "STRING");
        m.put("classId", "STRING");
        m.put("tableId", "STRING");
        m.put("contractId", "STRING");
        m.put("signature", "STRING");
        m.put("returnType", "STRING");
        m.put("paramCount", "INT64");
        m.put("receiver", "STRING");
        m.put("type", "STRING");
        m.put("visibility", "STRING");
        m.put("isStatic", "BOOLEAN");
        m.put("isAbstract", "BOOLEAN");
        m.put("isTest", "BOOLEAN");
        m.put("isAsync", "BOOLEAN");
        m.put("isConstructor", "BOOLEAN");
        m.put("isEntry", "BOOLEAN");
        m.put("isQueueListener", "BOOLEAN");
        m.put("isScheduled", "BOOLEAN");
        m.put("isExternal", "BOOLEAN");
        m.put("isBase", "BOOLEAN");
        m.put("isAnonymousHandler", "BOOLEAN");
        m.put("isClassmethod", "BOOLEAN");
        m.put("isDataclass", "BOOLEAN");
        m.put("isFinal", "BOOLEAN");
        m.put("isFixture", "BOOLEAN");
        m.put("isProperty", "BOOLEAN");
        m.put("cssModule", "BOOLEAN");
        m.put("moduleExport", "BOOLEAN");
        m.put("decorators", "STRING");
        m.put("package", "STRING");
        m.put("namespace", "STRING");
        m.put("value", "STRING");
        m.put("defaultValue", "STRING");
        m.put("valueType", "STRING");
        m.put("httpMethod", "STRING");
        m.put("httpPath", "STRING");
        m.put("framework", "STRING");
        m.put("viewRef", "STRING");
        m.put("scope", "STRING");
        m.put("source", "STRING");
        m.put("resourceType", "STRING");
        m.put("provider", "STRING");
        m.put("groupId", "STRING");
        m.put("artifactId", "STRING");
        m.put("version", "STRING");
        m.put("versionSource", "STRING");
        m.put("repository", "STRING");
        m.put("tag", "STRING");
        m.put("digest", "STRING");
        m.put("baseImage", "STRING");
        m.put("port", "INT64");
        m.put("protocol", "STRING");
        m.put("command", "STRING");
        m.put("rootPath", "STRING");
        m.put("lastScanCommit", "STRING");
        m.put("lastIngestedAt", "TIMESTAMP");
        m.put("contentHash", "STRING");
        // fileContentHash is the SHA-256 of the file's raw bytes (only ever set on File nodes).
        // Used by the re-scan incremental path to skip parsing files whose contents are
        // byte-identical to the previous scan. Distinct from `contentHash` which is the
        // per-node property-map hash used internally by the ingestor.
        m.put("fileContentHash", "STRING");
        return m;
    }

    private static final String NODE_DDL = buildNodeDdl();

    private static String buildNodeDdl() {
        StringBuilder sb = new StringBuilder("CREATE NODE TABLE IF NOT EXISTS Node(");
        for (Map.Entry<String, String> e : NODE_COLUMNS.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append(", ");
        }
        sb.append("PRIMARY KEY (id))");
        return sb.toString();
    }

    /** Edge types cvector emits. New types must be added here before parsers can use them. */
    public static final List<String> EDGE_TYPES = List.of(
            "CONTAINS", "CALLS", "EXTENDS", "IMPLEMENTS", "IMPORTS",
            "EXPOSES", "HANDLES", "DEPENDS_ON", "DECLARES",
            "READS_TABLE", "WRITES_TABLE", "READS_CONFIG",
            "USES", "REFERENCES",
            "REFERENCES_COMPONENT",
            "CALLS_HTTP"
    );

    private final EmbeddedKuzu kuzu;

    public KuzuSchemaBootstrap(EmbeddedKuzu kuzu) {
        this.kuzu = kuzu;
    }

    public void bootstrap() {
        try {
            kuzu.write(NODE_DDL, Map.of());
            log.debug("Kuzu Node table ready");
        } catch (RuntimeException e) {
            log.warn("could not create Node table: {}", e.getMessage());
        }
        addMissingNodeColumns();
        for (String type : EDGE_TYPES) {
            String ddl = "CREATE REL TABLE IF NOT EXISTS " + type
                    + "(FROM Node TO Node, "
                    + "confidence DOUBLE, callSiteLine INT64, "
                    + "kind STRING, via STRING, "
                    + "viaMethodReference BOOLEAN, ambiguous BOOLEAN)";
            try {
                kuzu.write(ddl, Map.of());
            } catch (RuntimeException e) {
                log.warn("could not create rel table {}: {}", type, e.getMessage());
            }
        }
    }

    /**
     * Bring an existing Node table up to the current column set. We query the live column list via
     * {@code TABLE_INFO}, diff against {@link #NODE_COLUMNS}, and {@code ALTER TABLE Node ADD ...}
     * each missing one. {@code IF NOT EXISTS} on ALTER would be ideal but isn't universally
     * supported in 0.11.3, so we filter first.
     */
    private void addMissingNodeColumns() {
        Set<String> existing;
        try {
            List<Map<String, Object>> rows = kuzu.read("CALL TABLE_INFO('Node') RETURN name");
            existing = new java.util.HashSet<>();
            for (Map<String, Object> r : rows) {
                Object n = r.get("name");
                if (n != null) existing.add(n.toString());
            }
        } catch (RuntimeException e) {
            log.debug("TABLE_INFO('Node') failed, skipping column add pass: {}", e.getMessage());
            return;
        }
        for (Map.Entry<String, String> e : NODE_COLUMNS.entrySet()) {
            if (existing.contains(e.getKey())) continue;
            String alter = "ALTER TABLE Node ADD " + e.getKey() + " " + e.getValue();
            try {
                kuzu.write(alter, Map.of());
                log.info("added Node.{} ({})", e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn("could not add Node.{}: {}", e.getKey(), ex.getMessage());
            }
        }
    }
}
