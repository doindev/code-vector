package io.doindev.cvector.embedded;

import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.core.store.GraphStore;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GraphStore} backed by an embedded KuzuDB. Wraps {@link EmbeddedKuzu} and routes Neo4j-shaped
 * read queries through the polymorphic {@code Node} table.
 *
 * <p>Performance: each cvector project lives in its own Kuzu directory, so the {@code projectId}
 * predicate every Neo4j query uses is a no-op here. The Kuzu queries skip it to avoid the scan
 * cost of a redundant string equality on every row.
 *
 * <p>Subtleties vs Neo4j:
 * <ul>
 *   <li>Node labels are a property column, not first-class types, so {@code labels(n)[0]} →
 *       {@code n.label}.</li>
 *   <li>No synthetic "any relationship" table — edge counts fan out across the 14 REL tables.</li>
 *   <li>No regex {@code =~} support; substring search uses {@code lower(name) CONTAINS lower(q)}.
 *       {@code *} wildcards split the query into AND-chained CONTAINS clauses.</li>
 * </ul>
 */
public final class KuzuGraphStore implements GraphStore {

    private final EmbeddedKuzu kuzu;
    private final String displayUri;

    public KuzuGraphStore(EmbeddedKuzu kuzu) {
        this.kuzu = kuzu;
        this.displayUri = "kuzu://" + kuzu.dbPath();
    }

    public EmbeddedKuzu kuzu() { return kuzu; }

    @Override
    public boolean ping() { return kuzu.ping(); }

    @Override
    public String displayUri() { return displayUri; }

    @Override
    public void bootstrapSchema() {
        new KuzuSchemaBootstrap(kuzu).bootstrap();
    }

    @Override
    public boolean schemaReady() {
        try {
            List<Map<String, Object>> rows = kuzu.read("CALL SHOW_TABLES() RETURN name");
            for (Map<String, Object> r : rows) {
                if ("Node".equals(String.valueOf(r.get("name")))) return true;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Map<String, Long> nodeCounts(String projectId) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) RETURN n.label AS label, count(n) AS c ORDER BY label");
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String label = String.valueOf(r.get("label"));
            out.put(label, asLong(r.get("c")));
        }
        return out;
    }

    @Override
    public Map<String, Long> edgeCounts(String projectId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String type : KuzuSchemaBootstrap.EDGE_TYPES) {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH ()-[r:" + type + "]->() RETURN count(r) AS c");
            long count = rows.isEmpty() ? 0L : asLong(rows.get(0).get("c"));
            if (count > 0) out.put(type, count);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> findSymbol(String projectId, String symbol) {
        // Three candidate predicates: exact fqName, fqName ending in `.symbol`, or simple name.
        // Kuzu doesn't support Neo4j's `ENDS WITH '.' + $sym` string concat in the predicate, so
        // pass the suffix as a separate parameter.
        return kuzu.read(
                "MATCH (n:Node) WHERE n.fqName = $sym OR n.fqName ENDS WITH $suffix OR n.name = $sym "
                        + "RETURN n.label AS label, n.fqName AS fqName, n.name AS name, "
                        + "n.id AS id, n.startLine AS startLine, n.fileId AS fileId LIMIT 25",
                Map.of("sym", symbol, "suffix", "." + symbol));
    }

    @Override
    public List<Map<String, Object>> searchByName(String projectId, String query, String label, int limit) {
        // Build an AND-chained CONTAINS predicate for each * segment, against lower(n.name) and
        // lower(n.fqName). For a query like "foo*bar" we want results containing both substrings.
        String[] segments = query.toLowerCase().split("\\*");
        StringBuilder predicate = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lim", limit);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) continue;
            if (predicate.length() > 0) predicate.append(" AND ");
            String paramName = "s" + i;
            // Match either name or fqName (case-insensitive)
            predicate.append("(lower(n.name) CONTAINS $").append(paramName)
                    .append(" OR lower(n.fqName) CONTAINS $").append(paramName).append(")");
            params.put(paramName, seg);
        }
        if (predicate.length() == 0) {
            return List.of();
        }
        StringBuilder cypher = new StringBuilder("MATCH (n:Node) WHERE ").append(predicate);
        if (label != null && !label.isEmpty()) {
            cypher.append(" AND n.label = $label");
            params.put("label", label);
        }
        cypher.append(" RETURN n.label AS label, n.fqName AS fqName, n.name AS name, n.id AS id LIMIT $lim");
        return kuzu.read(cypher.toString(), params);
    }

    @Override
    public List<Map<String, Object>> callers(String projectId, String id) {
        return kuzu.read(
                "MATCH (caller:Node)-[r:CALLS]->(callee:Node {id: $id}) "
                        + "WHERE caller.label = 'Method' "
                        + "RETURN caller.fqName AS fqName, caller.name AS name, "
                        + "r.callSiteLine AS callSiteLine, caller.id AS id LIMIT 100",
                Map.of("id", id));
    }

    @Override
    public List<Map<String, Object>> callees(String projectId, String id) {
        return kuzu.read(
                "MATCH (caller:Node {id: $id})-[r:CALLS]->(callee:Node) "
                        + "WHERE callee.label = 'Method' "
                        + "RETURN callee.fqName AS fqName, callee.name AS name, "
                        + "r.callSiteLine AS callSiteLine, callee.id AS id LIMIT 100",
                Map.of("id", id));
    }

    @Override
    public List<Map<String, Object>> impactDownstream(String projectId, String id, int depth) {
        int safeDepth = Math.max(1, Math.min(depth, 8));
        // Kuzu supports variable-length paths via `*1..N` on a relationship pattern. To traverse
        // both CALLS and REFERENCES we'd want `[:CALLS|REFERENCES*1..N]` — Kuzu may or may not
        // accept the union; fall back to CALLS-only and union REFERENCES in a follow-up query if
        // needed. Distinct on id collapses repeat paths.
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            rows.addAll(kuzu.read(
                    "MATCH (start:Node {id: $id})-[:CALLS*1.." + safeDepth + "]->(impacted:Node) "
                            + "RETURN DISTINCT impacted.label AS label, impacted.fqName AS fqName, "
                            + "impacted.name AS name, impacted.id AS id LIMIT 500",
                    Map.of("id", id)));
        } catch (RuntimeException ignored) { /* skip CALLS branch if unsupported */ }
        try {
            rows.addAll(kuzu.read(
                    "MATCH (start:Node {id: $id})-[:REFERENCES*1.." + safeDepth + "]->(impacted:Node) "
                            + "RETURN DISTINCT impacted.label AS label, impacted.fqName AS fqName, "
                            + "impacted.name AS name, impacted.id AS id LIMIT 500",
                    Map.of("id", id)));
        } catch (RuntimeException ignored) { /* skip REFERENCES branch if unsupported */ }
        return dedupById(rows);
    }

    private static List<Map<String, Object>> dedupById(List<Map<String, Object>> rows) {
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byId.putIfAbsent(r.get("id"), r);
        return new ArrayList<>(byId.values());
    }

    @Override
    public Map<String, Object> fileOf(String projectId, String fileId) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (f:Node {id: $id}) WHERE f.label = 'File' "
                        + "RETURN f.path AS path, f.language AS language",
                Map.of("id", fileId));
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.get(0));
    }

    @Override
    public Map<String, Object> projectMeta(String projectId) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (p:Node) WHERE p.label = 'Project' "
                        + "RETURN p.lastScanCommit AS lastScanCommit, p.rootPath AS rootPath LIMIT 1");
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.get(0));
    }

    @Override
    public List<Map<String, Object>> contains(String projectId, String id, int limit) {
        return kuzu.read(
                "MATCH (n:Node {id: $id})-[:CONTAINS]->(child:Node) "
                        + "RETURN child.label AS label, child.fqName AS fqName LIMIT $lim",
                Map.of("id", id, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> referencingNodes(String projectId, String id, int limit) {
        return kuzu.read(
                "MATCH (src:Node)-[:REFERENCES]->(target:Node {id: $id}) "
                        + "RETURN src.label AS label, src.fqName AS fqName, "
                        + "src.fileId AS fileId, src.startLine AS line LIMIT $lim",
                Map.of("id", id, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> importingFiles(String projectId, String id, int limit) {
        return kuzu.read(
                "MATCH (f:Node)-[:IMPORTS]->(target:Node {id: $id}) "
                        + "WHERE f.label = 'File' "
                        + "RETURN f.path AS path LIMIT $lim",
                Map.of("id", id, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> recentlyChanged(String projectId, Duration since, int limit) {
        String cutoff = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                Instant.now().minus(since).atOffset(ZoneOffset.UTC));
        return kuzu.read(
                "MATCH (n:Node) WHERE n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt > timestamp($cutoff) "
                        + "RETURN n.label AS label, n.fqName AS fqName, "
                        + "cast(n.lastIngestedAt AS STRING) AS lastIngestedAt "
                        + "ORDER BY n.lastIngestedAt DESC LIMIT $lim",
                Map.of("cutoff", cutoff, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> mavenDependencies(String projectId) {
        return kuzu.read(
                "MATCH (d:Node) WHERE d.label = 'MavenDependency' "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, "
                        + "d.version AS version, d.scope AS scope "
                        + "ORDER BY d.groupId, d.artifactId");
    }

    @Override
    public List<Map<String, Object>> fileInventory(String projectId) {
        return kuzu.read(
                "MATCH (f:Node) WHERE f.label = 'File' "
                        + "OPTIONAL MATCH (f)-[:CONTAINS]->(m:Node) "
                        + "WITH f, count(m) AS methodCount "
                        + "RETURN f.path AS path, f.language AS language, f.lineCount AS lineCount, "
                        + "       methodCount, cast(f.lastIngestedAt AS STRING) AS lastIngestedAt "
                        + "ORDER BY f.path");
    }

    @Override
    public Map<String, List<Map<String, Object>>> healthRollup(String projectId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("godFiles", kuzu.read(
                "MATCH (f:Node)-[:CONTAINS]->(c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE f.label = 'File' AND c.label = 'Class' AND m.label = 'Method' "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 "
                        + "RETURN f.path AS path, methods ORDER BY methods DESC LIMIT 20"));
        out.put("godClasses", kuzu.read(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 "
                        + "RETURN c.fqName AS fqName, methods ORDER BY methods DESC LIMIT 20"));
        out.put("longMethods", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                        + "AND (m.endLine - m.startLine) >= 80 "
                        + "RETURN m.fqName AS fqName, (m.endLine - m.startLine) AS lines "
                        + "ORDER BY lines DESC LIMIT 20"));
        // Dead code: methods with no incoming CALLS, excluding tests, mains, constructors,
        // and only flagging private/package visibility. Kuzu doesn't support EXISTS subqueries,
        // so we OPTIONAL MATCH the incoming edges and filter on count = 0.
        out.put("deadCode", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND coalesce(m.isTest, false) = false "
                        + "AND m.name <> 'main' "
                        + "AND coalesce(m.isConstructor, false) = false "
                        + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                        + "OPTIONAL MATCH ()-[r:CALLS]->(m) "
                        + "WITH m, count(r) AS incoming "
                        + "WHERE incoming = 0 "
                        + "RETURN m.fqName AS fqName ORDER BY m.fqName LIMIT 20"));
        return out;
    }

    @Override
    public Map<String, Object> guardSummary(String projectId) {
        long godFiles = countOne(
                "MATCH (f:Node)-[:CONTAINS]->(c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE f.label = 'File' AND c.label = 'Class' AND m.label = 'Method' "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 RETURN count(f) AS c");
        long godClasses = countOne(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 RETURN count(c) AS c");
        long longMethods = countOne(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND (m.endLine - m.startLine) >= 80 RETURN count(m) AS c");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", godFiles == 0 && godClasses == 0);
        out.put("errors", godFiles + godClasses);
        out.put("warnings", longMethods);
        out.put("breakdown", Map.of(
                "godFiles", godFiles,
                "godClasses", godClasses,
                "longMethods", longMethods));
        return out;
    }

    private long countOne(String cypher) {
        List<Map<String, Object>> rows = kuzu.read(cypher);
        if (rows.isEmpty()) return 0L;
        return asLong(rows.get(0).get("c"));
    }

    @Override
    public Map<String, List<Map<String, Object>>> infrastructureSummary(String projectId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("apiEndpoints", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'ApiEndpoint' "
                        + "RETURN e.httpMethod AS httpMethod, e.path AS path, e.framework AS framework "
                        + "ORDER BY path"));
        out.put("queueListeners", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS fqName"));
        out.put("scheduledJobs", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND coalesce(m.isScheduled, false) = true "
                        + "RETURN m.fqName AS fqName"));
        out.put("configKeys", kuzu.read(
                "MATCH (c:Node) WHERE c.label = 'ConfigKey' "
                        + "RETURN c.fqName AS fqName ORDER BY c.fqName"));
        out.put("envVars", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'EnvVar' "
                        + "RETURN e.fqName AS fqName, e.value AS value ORDER BY e.fqName"));
        out.put("containerImages", kuzu.read(
                "MATCH (i:Node) WHERE i.label = 'ContainerImage' "
                        + "RETURN i.fqName AS fqName, i.repository AS repository, i.tag AS tag"));
        out.put("containerPorts", kuzu.read(
                "MATCH (p:Node) WHERE p.label = 'ContainerPort' "
                        + "RETURN p.port AS port, p.protocol AS protocol"));
        out.put("terraformResources", kuzu.read(
                "MATCH (r:Node) WHERE r.label = 'Resource' "
                        + "RETURN r.fqName AS fqName, r.resourceType AS resourceType, r.provider AS provider"));
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> traceFlows(String projectId, String kind, int depth, int limit) {
        String k = kind == null || kind.isBlank() ? "all" : kind;
        int d = Math.max(1, Math.min(depth, 8));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if ("rest".equals(k) || "all".equals(k)) {
            // collect(DISTINCT ...)[0..50] is valid Cypher and works in Kuzu (probed).
            out.put("rest", kuzu.read(
                    "MATCH (e:Node)-[:HANDLES]->(handler:Node) "
                            + "WHERE e.label = 'ApiEndpoint' AND handler.label = 'Method' "
                            + "OPTIONAL MATCH (handler)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN e.httpMethod AS method, e.path AS path, handler.fqName AS handler, "
                            + "list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit)));
        }
        if ("main".equals(k) || "all".equals(k)) {
            out.put("main", kuzu.read(
                    "MATCH (m:Node) WHERE m.label = 'Method' AND m.name = 'main' "
                            + "AND coalesce(m.isStatic, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN m.fqName AS entry, list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit)));
        }
        if ("test".equals(k) || "all".equals(k)) {
            out.put("test", kuzu.read(
                    "MATCH (m:Node) WHERE m.label = 'Method' "
                            + "AND coalesce(m.isTest, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN m.fqName AS entry, list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit)));
        }
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> serviceLinks(String projectId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("outgoingHttp", kuzu.read(
                "MATCH (m:Node)-[:CALLS]->(callee:Node) "
                        + "WHERE m.label = 'Method' AND callee.label = 'Method' "
                        + "AND regexp_matches(callee.fqName, '(?i).*(RestTemplate|WebClient|HttpClient|FeignClient|OkHttpClient).*') "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                        + "ORDER BY calls DESC LIMIT 50"));
        out.put("outgoingMessaging", kuzu.read(
                "MATCH (m:Node)-[:CALLS]->(callee:Node) "
                        + "WHERE m.label = 'Method' AND callee.label = 'Method' "
                        + "AND regexp_matches(callee.fqName, '(?i).*(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|SqsTemplate|SnsTemplate).*') "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                        + "ORDER BY calls DESC LIMIT 50"));
        out.put("incomingConsumers", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS handler LIMIT 100"));
        out.put("restEndpoints", kuzu.read(
                "MATCH (e:Node)-[:HANDLES]->(m:Node) "
                        + "WHERE e.label = 'ApiEndpoint' AND m.label = 'Method' "
                        + "RETURN e.httpMethod AS method, e.path AS path, m.fqName AS handler ORDER BY path"));
        // tablesTouched: we can't easily distinguish READS_TABLE vs WRITES_TABLE in a single MATCH
        // on Kuzu without rel-table union syntax. Issue two queries and tag the access type.
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String rel : List.of("READS_TABLE", "WRITES_TABLE")) {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (n:Node)-[r:" + rel + "]->(t:Node) WHERE t.label = 'Table' "
                            + "RETURN t.name AS `table`, count(DISTINCT n) AS sources "
                            + "ORDER BY `table`");
            for (Map<String, Object> r : rows) {
                Map<String, Object> tagged = new LinkedHashMap<>(r);
                tagged.put("access", rel);
                tables.add(tagged);
            }
        }
        out.put("tablesTouched", tables);
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> onboardSummary(String projectId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("languages", kuzu.read(
                "MATCH (f:Node) WHERE f.label = 'File' "
                        + "RETURN f.language AS language, count(f) AS files ORDER BY files DESC"));
        out.put("topClasses", kuzu.read(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' "
                        + "RETURN c.fqName AS fqName, count(m) AS methods ORDER BY methods DESC LIMIT 10"));
        out.put("restEndpoints", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'ApiEndpoint' "
                        + "RETURN e.httpMethod AS method, e.path AS path, e.framework AS framework ORDER BY path"));
        // Two-step: list all tables, then fetch table→column counts, merge in Java. Kuzu's
        // binder doesn't keep the outer-MATCH variable in scope through an OPTIONAL MATCH +
        // WITH chain here, so we compose the result instead of one composite Cypher.
        List<Map<String, Object>> tableRows = kuzu.read(
                "MATCH (t:Node) WHERE t.label = 'Table' RETURN t.name AS `table` ORDER BY t.name");
        List<Map<String, Object>> colCounts = kuzu.read(
                "MATCH (t:Node)-[:CONTAINS]->(c:Node) "
                        + "WHERE t.label = 'Table' AND c.label = 'Column' "
                        + "RETURN t.name AS `table`, count(c) AS columns");
        java.util.Map<Object, Object> byTable = new java.util.HashMap<>();
        for (Map<String, Object> r : colCounts) byTable.put(r.get("table"), r.get("columns"));
        List<Map<String, Object>> tables = new ArrayList<>(tableRows.size());
        for (Map<String, Object> r : tableRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", r.get("table"));
            row.put("columns", byTable.getOrDefault(r.get("table"), 0L));
            tables.add(row);
        }
        out.put("tables", tables);
        out.put("configKeys", kuzu.read(
                "MATCH (k:Node) WHERE k.label = 'ConfigKey' "
                        + "RETURN k.fqName AS key, k.value AS value ORDER BY k.fqName LIMIT 50"));
        out.put("envVars", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'EnvVar' "
                        + "RETURN e.name AS name, e.value AS value ORDER BY e.name"));
        out.put("callGraphHubs", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' "
                        + "AND NOT m.fqName STARTS WITH 'unresolved.' "
                        + "OPTIONAL MATCH (m)-[outR:CALLS]->() "
                        + "WITH m, count(outR) AS outDeg "
                        + "OPTIONAL MATCH ()-[inR:CALLS]->(m) "
                        + "WITH m, outDeg, count(inR) AS inDeg "
                        + "WHERE (outDeg + inDeg) > 0 "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, (outDeg + inDeg) AS total "
                        + "ORDER BY total DESC LIMIT 10"));
        return out;
    }

    @Override
    public List<Map<String, Object>> projectsList() {
        // Each embedded Kuzu DB holds exactly one project — the active one.
        return kuzu.read(
                "MATCH (p:Node) WHERE p.label = 'Project' "
                        + "OPTIONAL MATCH (p)<-[:CONTAINS]-(f:Node) WHERE f.label = 'File' "
                        + "WITH p, count(f) AS fileCount "
                        + "RETURN p.projectId AS projectId, p.name AS name, p.rootPath AS rootPath, "
                        + "       p.lastScanCommit AS lastScanCommit, fileCount "
                        + "ORDER BY p.name");
    }

    @Override
    public MethodCallGraph methodCallGraph(String projectId) {
        List<Map<String, Object>> methods = kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' RETURN m.id AS id, m.fqName AS fqName");
        java.util.Map<String, Integer> idx = new java.util.HashMap<>(methods.size() * 2);
        String[] fqNames = new String[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            String id = (String) methods.get(i).get("id");
            idx.put(id, i);
            fqNames[i] = (String) methods.get(i).get("fqName");
        }
        List<Map<String, Object>> edges = kuzu.read(
                "MATCH (a:Node)-[:CALLS]->(b:Node) "
                        + "WHERE a.label = 'Method' AND b.label = 'Method' "
                        + "RETURN a.id AS fromId, b.id AS toId");
        List<int[]> edgePairs = new ArrayList<>(edges.size());
        for (Map<String, Object> e : edges) {
            Integer fi = idx.get(e.get("fromId"));
            Integer ti = idx.get(e.get("toId"));
            if (fi != null && ti != null) edgePairs.add(new int[]{fi, ti});
        }
        return new MethodCallGraph(fqNames, edgePairs);
    }

    @Override
    public List<Map<String, Object>> testReach(String projectId, String id, int maxDepth) {
        // Native variable-length traversal: union-edge `[:CALLS|REFERENCES*1..D]` from each test
        // method to the target. `min(length(p))` collapses multiple paths into the shortest depth.
        // Replaces the prior "fetch every CALLS+REFERENCES edge then BFS in Java" approach —
        // Kuzu can prune paths via its graph index, so this scales as the graph grows.
        int d = Math.max(1, Math.min(maxDepth, 16));
        return kuzu.read(
                "MATCH p = (t:Node)-[:CALLS|REFERENCES*1.." + d + "]->(target:Node {id: $id}) "
                        + "WHERE t.label = 'Method' AND coalesce(t.isTest, false) = true "
                        + "WITH t, min(length(p)) AS depth "
                        + "RETURN t.fqName AS test, t.fileId AS fileId, depth "
                        + "ORDER BY depth ASC LIMIT 200",
                Map.of("id", id));
    }

    @Override
    public String backend() { return "kuzu"; }

    @Override
    public RawResult rawCypher(String cypher, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        if (looksLikeWrite(cypher)) {
            kuzu.write(cypher, p);
            return new RawResult(true, List.of());
        }
        return new RawResult(false, kuzu.read(cypher, p));
    }

    @Override
    public GraphIngestor openIngestor() { return new KuzuIngestor(kuzu); }

    @Override
    public GraphIngestor openBulkIngestor() { return new KuzuBulkLoader(kuzu); }

    @Override
    public boolean isEmpty(String projectId) {
        List<Map<String, Object>> rows = kuzu.read("MATCH (n:Node) RETURN count(n) AS c");
        return rows.isEmpty() || asLong(rows.get(0).get("c")) == 0;
    }

    @Override
    public int deleteFileSubtree(String projectId, String path) {
        // Find the File node by path. If absent, nothing to delete.
        List<Map<String, Object>> root = kuzu.read(
                "MATCH (f:Node) WHERE f.label = 'File' AND f.path = $path RETURN f.id AS id LIMIT 1",
                Map.of("path", path));
        if (root.isEmpty()) return 0;
        String rootId = (String) root.get(0).get("id");

        // BFS via CONTAINS to collect every descendant id. Kuzu can't do variable-length
        // DETACH DELETE atomically, so we materialise the closure and delete node-by-node.
        java.util.Set<String> toDelete = new java.util.LinkedHashSet<>();
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>();
        toDelete.add(rootId);
        frontier.add(rootId);
        while (!frontier.isEmpty()) {
            String cur = frontier.poll();
            List<Map<String, Object>> children = kuzu.read(
                    "MATCH (n:Node {id: $id})-[:CONTAINS]->(c:Node) RETURN c.id AS id",
                    Map.of("id", cur));
            for (Map<String, Object> r : children) {
                String childId = (String) r.get("id");
                if (childId != null && toDelete.add(childId)) frontier.add(childId);
            }
        }

        for (String id : toDelete) {
            kuzu.write("MATCH (n:Node {id: $id}) DETACH DELETE n", Map.of("id", id));
        }
        return toDelete.size();
    }

    private static boolean looksLikeWrite(String cypher) {
        if (cypher == null) return false;
        String upper = cypher.toUpperCase();
        return upper.contains("CREATE ") || upper.contains("MERGE ") || upper.contains(" SET ")
                || upper.contains("DELETE ") || upper.contains("REMOVE ") || upper.contains("DROP ")
                || upper.contains("ALTER ") || upper.contains("BEGIN TRANSACTION") || upper.contains("COMMIT")
                || upper.contains("ROLLBACK");
    }

    @Override
    public void close() { kuzu.close(); }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }
}
