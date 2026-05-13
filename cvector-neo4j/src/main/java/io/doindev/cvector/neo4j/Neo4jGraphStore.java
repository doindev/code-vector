package io.doindev.cvector.neo4j;

import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.neo4j.repo.GraphQueries;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@link GraphStore} backed by the Bolt driver. Owns the {@link Neo4jClient}'s lifecycle —
 * closing this closes the driver. All read methods delegate to {@link GraphQueries}; schema
 * methods route through {@link SchemaBootstrap}.
 */
public final class Neo4jGraphStore implements GraphStore {

    private final Neo4jClient client;
    private final GraphQueries queries;
    private final SchemaBootstrap schema;

    public Neo4jGraphStore(Neo4jClient client) {
        this.client = client;
        this.queries = new GraphQueries(client);
        this.schema = new SchemaBootstrap(client);
    }

    public Neo4jClient client() { return client; }

    public GraphQueries queries() { return queries; }

    @Override
    public boolean ping() { return client.ping(); }

    @Override
    public String displayUri() { return client.uri(); }

    @Override
    public void bootstrapSchema() { schema.bootstrap(); }

    @Override
    public boolean schemaReady() { return schema.hasConstraints(); }

    @Override
    public Map<String, Long> nodeCounts(String projectId) { return queries.nodeCounts(projectId); }

    @Override
    public Map<String, Long> edgeCounts(String projectId) { return queries.edgeCounts(projectId); }

    @Override
    public List<Map<String, Object>> schemaConnectivity(String projectId) {
        // Single Cypher: aggregate all edges by (fromLabel, toLabel, edgeType). Neo4j stores
        // each rel as its own type, so we project the first label of each endpoint (cvector
        // emits nodes with exactly one label) plus type(r). Output sorted heaviest-first to
        // match Kuzu's contract.
        return queries.raw(
                "MATCH (a)-[r]->(b) "
                        + "WHERE a.projectId = $pid AND b.projectId = $pid "
                        + "WITH labels(a)[0] AS f, labels(b)[0] AS t, type(r) AS k, count(r) AS c "
                        + "WHERE c > 0 "
                        + "RETURN f AS `from`, t AS `to`, k AS type, c AS count "
                        + "ORDER BY count DESC, `from`, `to`, type",
                Map.of("pid", projectId)
        );
    }

    @Override
    public List<Map<String, Object>> findSymbol(String projectId, String symbol) {
        return queries.findSymbol(projectId, symbol);
    }

    @Override
    public List<Map<String, Object>> searchByName(String projectId, String query, String label, int limit) {
        String regex = "(?i).*" + query.replace("*", ".*") + ".*";
        String labelFilter = label != null && !label.isEmpty()
                ? "AND any(l IN labels(n) WHERE l = $label) " : "";
        String cypher = "MATCH (n) WHERE n.projectId = $pid "
                + "AND (n.fqName =~ $regex OR n.name =~ $regex) " + labelFilter
                + "RETURN labels(n)[0] AS label, n.fqName AS fqName, n.name AS name, n.id AS id LIMIT $lim";
        return queries.raw(cypher, Map.of(
                "pid", projectId,
                "regex", regex,
                "label", label == null ? "" : label,
                "lim", limit));
    }

    @Override
    public List<Map<String, Object>> callers(String projectId, String id) {
        return queries.callers(projectId, id);
    }

    @Override
    public List<Map<String, Object>> callees(String projectId, String id) {
        return queries.callees(projectId, id);
    }

    @Override
    public List<Map<String, Object>> impactDownstream(String projectId, String id, int depth) {
        return queries.impactDownstream(projectId, id, depth);
    }

    @Override
    public Map<String, Object> fileOf(String projectId, String fileId) {
        return queries.fileOf(projectId, fileId);
    }

    @Override
    public Map<String, Object> projectMeta(String projectId) {
        List<Map<String, Object>> rows = queries.raw(
                "MATCH (p:Project {projectId: $pid}) "
                        + "RETURN p.lastScanCommit AS lastScanCommit, p.rootPath AS rootPath",
                Map.of("pid", projectId));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @Override
    public List<Map<String, Object>> contains(String projectId, String id, int limit) {
        return queries.raw(
                "MATCH (n {id: $id, projectId: $pid})-[:CONTAINS]->(child) "
                        + "RETURN labels(child)[0] AS label, child.fqName AS fqName LIMIT $lim",
                Map.of("id", id, "pid", projectId, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> referencingNodes(String projectId, String id, int limit) {
        return queries.raw(
                "MATCH (src)-[:REFERENCES]->(target {id: $id, projectId: $pid}) "
                        + "RETURN labels(src)[0] AS label, src.fqName AS fqName, "
                        + "src.fileId AS fileId, src.startLine AS line LIMIT $lim",
                Map.of("id", id, "pid", projectId, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> importingFiles(String projectId, String id, int limit) {
        return queries.raw(
                "MATCH (f:File)-[:IMPORTS]->(target {id: $id, projectId: $pid}) "
                        + "RETURN f.path AS path LIMIT $lim",
                Map.of("id", id, "pid", projectId, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> recentlyChanged(String projectId, Duration since, int limit) {
        String cutoff = ZonedDateTime.now(ZoneOffset.UTC).minus(since).toString();
        return queries.raw(
                "MATCH (n) WHERE n.projectId = $pid AND n.lastIngestedAt > datetime($cutoff) "
                        + "RETURN labels(n)[0] AS label, n.fqName AS fqName, "
                        + "toString(n.lastIngestedAt) AS lastIngestedAt "
                        + "ORDER BY n.lastIngestedAt DESC LIMIT $lim",
                Map.of("pid", projectId, "cutoff", cutoff, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> mavenDependencies(String projectId) {
        return queries.raw(
                "MATCH (d:MavenDependency {projectId: $pid}) "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, "
                        + "d.version AS version, d.scope AS scope "
                        + "ORDER BY groupId, artifactId",
                Map.of("pid", projectId));
    }

    @Override
    public List<Map<String, Object>> fileInventory(String projectId) {
        return queries.raw(
                "MATCH (f:File {projectId: $pid}) "
                        + "OPTIONAL MATCH (f)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methodCount "
                        + "RETURN f.path AS path, f.language AS language, f.lineCount AS lineCount, "
                        + "       methodCount, toString(f.lastIngestedAt) AS lastIngestedAt "
                        + "ORDER BY f.path",
                Map.of("pid", projectId));
    }

    @Override
    public Map<String, List<Map<String, Object>>> healthRollup(String projectId) {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        out.put("godFiles", queries.raw(
                "MATCH (f:File {projectId: $pid})-[:CONTAINS]->(:Class)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 "
                        + "RETURN f.path AS path, methods ORDER BY methods DESC LIMIT 20",
                Map.of("pid", projectId)));
        out.put("godClasses", queries.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 "
                        + "RETURN c.fqName AS fqName, methods ORDER BY methods DESC LIMIT 20",
                Map.of("pid", projectId)));
        out.put("longMethods", queries.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                        + "AND (m.endLine - m.startLine) >= 80 "
                        + "RETURN m.fqName AS fqName, (m.endLine - m.startLine) AS lines "
                        + "ORDER BY lines DESC LIMIT 20",
                Map.of("pid", projectId)));
        out.put("deadCode", queries.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE NOT EXISTS { MATCH ()-[:CALLS]->(m) } "
                        + "AND coalesce(m.isTest, false) = false "
                        + "AND m.name <> 'main' "
                        + "AND coalesce(m.isConstructor, false) = false "
                        + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                        + "RETURN m.fqName AS fqName ORDER BY m.fqName LIMIT 20",
                Map.of("pid", projectId)));
        return out;
    }

    @Override
    public Map<String, Object> guardSummary(String projectId) {
        long godFiles = countOne(
                "MATCH (f:File {projectId: $pid})-[:CONTAINS]->(:Class)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 RETURN count(f) AS c",
                projectId);
        long godClasses = countOne(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 RETURN count(c) AS c",
                projectId);
        long longMethods = countOne(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE (m.endLine - m.startLine) >= 80 RETURN count(m) AS c",
                projectId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pass", godFiles == 0 && godClasses == 0);
        out.put("errors", godFiles + godClasses);
        out.put("warnings", longMethods);
        out.put("breakdown", Map.of(
                "godFiles", godFiles,
                "godClasses", godClasses,
                "longMethods", longMethods));
        return out;
    }

    private long countOne(String cypher, String projectId) {
        List<Map<String, Object>> rows = queries.raw(cypher, Map.of("pid", projectId));
        if (rows.isEmpty()) return 0L;
        Object v = rows.get(0).get("c");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public Map<String, List<Map<String, Object>>> infrastructureSummary(String projectId) {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        out.put("apiEndpoints", queries.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "RETURN e.httpMethod AS httpMethod, e.path AS path, e.framework AS framework "
                        + "ORDER BY path",
                Map.of("pid", projectId)));
        out.put("queueListeners", queries.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS fqName",
                Map.of("pid", projectId)));
        out.put("scheduledJobs", queries.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isScheduled, false) = true "
                        + "RETURN m.fqName AS fqName",
                Map.of("pid", projectId)));
        out.put("configKeys", queries.raw(
                "MATCH (c:ConfigKey {projectId: $pid}) RETURN c.fqName AS fqName ORDER BY c.fqName",
                Map.of("pid", projectId)));
        out.put("envVars", queries.raw(
                "MATCH (e:EnvVar {projectId: $pid}) RETURN e.fqName AS fqName, e.value AS value ORDER BY e.fqName",
                Map.of("pid", projectId)));
        out.put("containerImages", queries.raw(
                "MATCH (i:ContainerImage {projectId: $pid}) "
                        + "RETURN i.fqName AS fqName, i.repository AS repository, i.tag AS tag",
                Map.of("pid", projectId)));
        out.put("containerPorts", queries.raw(
                "MATCH (p:ContainerPort {projectId: $pid}) RETURN p.port AS port, p.protocol AS protocol",
                Map.of("pid", projectId)));
        out.put("terraformResources", queries.raw(
                "MATCH (r:Resource {projectId: $pid}) "
                        + "RETURN r.fqName AS fqName, r.resourceType AS resourceType, r.provider AS provider",
                Map.of("pid", projectId)));
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> traceFlows(String projectId, String kind, int depth, int limit) {
        String k = kind == null || kind.isBlank() ? "all" : kind;
        int d = Math.max(1, depth);
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        if ("rest".equals(k) || "all".equals(k)) {
            out.put("rest", queries.raw(
                    "MATCH (e:ApiEndpoint {projectId: $pid})-[:HANDLES]->(handler:Method) "
                            + "OPTIONAL MATCH (handler)-[:CALLS*1.." + d + "]->(c:Method {projectId: $pid}) "
                            + "RETURN e.httpMethod AS method, e.path AS path, handler.fqName AS handler, "
                            + "collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", projectId, "lim", limit)));
        }
        if ("main".equals(k) || "all".equals(k)) {
            out.put("main", queries.raw(
                    "MATCH (m:Method {projectId: $pid, name: 'main'}) WHERE coalesce(m.isStatic, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Method {projectId: $pid}) "
                            + "RETURN m.fqName AS entry, collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", projectId, "lim", limit)));
        }
        if ("test".equals(k) || "all".equals(k)) {
            out.put("test", queries.raw(
                    "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isTest, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Method {projectId: $pid}) "
                            + "RETURN m.fqName AS entry, collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", projectId, "lim", limit)));
        }
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> serviceLinks(String projectId) {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        // Outgoing HTTP from JVM patterns (CALLS to Spring/Java client classes) UNION the JS/TS
        // pattern (File -[CALLS_HTTP]-> HttpCall) emitted by the TS parser for axios/fetch.
        out.put("outgoingHttp", queries.raw(
                "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                        + "WHERE callee.fqName =~ '(?i).*(RestTemplate|WebClient|HttpClient|FeignClient|OkHttpClient).*' "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls, null AS client, null AS method "
                        + "UNION "
                        + "MATCH (f:File {projectId: $pid})-[:CALLS_HTTP]->(h:HttpCall) "
                        + "RETURN f.path AS caller, h.path AS target, 1 AS calls, h.framework AS client, h.httpMethod AS method "
                        + "ORDER BY target",
                Map.of("pid", projectId)));
        out.put("outgoingMessaging", queries.raw(
                "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                        + "WHERE callee.fqName =~ '(?i).*(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|SqsTemplate|SnsTemplate).*' "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                        + "ORDER BY calls DESC LIMIT 50",
                Map.of("pid", projectId)));
        out.put("incomingConsumers", queries.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS handler LIMIT 100",
                Map.of("pid", projectId)));
        // restEndpoints: every parser links File -[EXPOSES]-> ApiEndpoint; only some parsers
        // (Java/Spring, Python/Django) add the HANDLES edge to a Method. Querying via HANDLES
        // alone hides Express/Vue/etc. endpoints that have no method link. Use OPTIONAL MATCH
        // so HANDLES-less endpoints still appear with an empty handler column.
        out.put("restEndpoints", queries.raw(
                "MATCH (f:File {projectId: $pid})-[:EXPOSES]->(e:ApiEndpoint {projectId: $pid}) "
                        + "OPTIONAL MATCH (e)-[:HANDLES]->(m:Method) "
                        + "RETURN e.httpMethod AS method, e.path AS path, e.framework AS framework, "
                        + "f.path AS file, coalesce(m.fqName, '') AS handler ORDER BY path",
                Map.of("pid", projectId)));
        out.put("tablesTouched", queries.raw(
                "MATCH (n)-[r:READS_TABLE|WRITES_TABLE]->(t:Table {projectId: $pid}) "
                        + "RETURN t.name AS table, type(r) AS access, count(DISTINCT n) AS sources ORDER BY t.name",
                Map.of("pid", projectId)));
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> onboardSummary(String projectId) {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        out.put("languages", queries.raw(
                "MATCH (f:File {projectId: $pid}) RETURN f.language AS language, count(*) AS files ORDER BY files DESC",
                Map.of("pid", projectId)));
        out.put("topClasses", queries.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "RETURN c.fqName AS fqName, count(m) AS methods ORDER BY methods DESC LIMIT 10",
                Map.of("pid", projectId)));
        out.put("restEndpoints", queries.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "RETURN e.httpMethod AS method, e.path AS path, e.framework AS framework ORDER BY path",
                Map.of("pid", projectId)));
        out.put("tables", queries.raw(
                "MATCH (t:Table {projectId: $pid}) OPTIONAL MATCH (t)-[:CONTAINS]->(c:Column) "
                        + "RETURN t.name AS `table`, count(c) AS columns ORDER BY t.name",
                Map.of("pid", projectId)));
        out.put("configKeys", queries.raw(
                "MATCH (k:ConfigKey {projectId: $pid}) RETURN k.fqName AS key, k.value AS value "
                        + "ORDER BY k.fqName LIMIT 50",
                Map.of("pid", projectId)));
        out.put("envVars", queries.raw(
                "MATCH (e:EnvVar {projectId: $pid}) RETURN e.name AS name, e.value AS value ORDER BY e.name",
                Map.of("pid", projectId)));
        out.put("callGraphHubs", queries.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "OPTIONAL MATCH (m)-[out:CALLS]->() "
                        + "OPTIONAL MATCH ()-[in:CALLS]->(m) "
                        + "WITH m, count(DISTINCT out) AS outDeg, count(DISTINCT in) AS inDeg "
                        + "WHERE outDeg + inDeg > 0 "
                        + "AND NOT m.fqName STARTS WITH 'unresolved.' "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, (outDeg + inDeg) AS total "
                        + "ORDER BY total DESC LIMIT 10",
                Map.of("pid", projectId)));
        return out;
    }

    @Override
    public List<Map<String, Object>> projectsList() {
        return queries.raw(
                "MATCH (p:Project) "
                        + "OPTIONAL MATCH (p)<-[:IN_PROJECT]-(f:File) "
                        + "WITH p, count(f) AS fileCount "
                        + "RETURN p.projectId AS projectId, p.name AS name, p.rootPath AS rootPath, "
                        + "       p.lastScanCommit AS lastScanCommit, fileCount "
                        + "ORDER BY p.name",
                Map.of());
    }

    @Override
    public MethodCallGraph methodCallGraph(String projectId) {
        List<Map<String, Object>> methods = queries.raw(
                "MATCH (m:Method {projectId: $pid}) RETURN m.id AS id, m.fqName AS fqName",
                Map.of("pid", projectId));
        java.util.Map<String, Integer> idx = new java.util.HashMap<>(methods.size() * 2);
        String[] fqNames = new String[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            String id = (String) methods.get(i).get("id");
            idx.put(id, i);
            fqNames[i] = (String) methods.get(i).get("fqName");
        }
        List<Map<String, Object>> edges = queries.raw(
                "MATCH (a:Method {projectId: $pid})-[:CALLS]->(b:Method {projectId: $pid}) "
                        + "RETURN a.id AS fromId, b.id AS toId",
                Map.of("pid", projectId));
        List<int[]> edgePairs = new java.util.ArrayList<>(edges.size());
        for (Map<String, Object> e : edges) {
            Integer fi = idx.get(e.get("fromId"));
            Integer ti = idx.get(e.get("toId"));
            if (fi != null && ti != null) edgePairs.add(new int[]{fi, ti});
        }
        return new MethodCallGraph(fqNames, edgePairs);
    }

    @Override
    public List<Map<String, Object>> testReach(String projectId, String id, int maxDepth) {
        // Neo4j has shortestPath, so we use it directly for compactness. Falls back to plain
        // variable-length traversal if needed.
        int d = Math.max(1, maxDepth);
        return queries.raw(
                "MATCH (t:Method {projectId: $pid, isTest: true}) "
                        + "MATCH (sym {id: $id, projectId: $pid}) "
                        + "MATCH p = shortestPath((t)-[:CALLS|REFERENCES*1.." + d + "]->(sym)) "
                        + "RETURN DISTINCT t.fqName AS test, t.fileId AS fileId, length(p) AS depth "
                        + "ORDER BY depth ASC LIMIT 200",
                Map.of("pid", projectId, "id", id));
    }

    @Override
    public String backend() { return "neo4j"; }

    @Override
    public RawResult rawCypher(String cypher, Map<String, Object> params) {
        GraphQueries.QueryResult r = queries.rawAutoTyped(cypher, params == null ? Map.of() : params);
        return new RawResult(r.isWrite(), r.rows());
    }

    @Override
    public GraphIngestor openIngestor() { return new Ingestor(client); }

    @Override
    public boolean isEmpty(String projectId) {
        List<Map<String, Object>> rows = queries.raw(
                "MATCH (n) WHERE n.projectId = $pid RETURN count(n) AS c LIMIT 1",
                Map.of("pid", projectId));
        return rows.isEmpty() || asLong(rows.get(0).get("c")) == 0;
    }

    @Override
    public int deleteFileSubtree(String projectId, String path) {
        // Neo4j: variable-length CONTAINS* + DETACH DELETE handles the whole subtree atomically.
        List<Map<String, Object>> count = queries.raw(
                "MATCH (f:File {projectId: $pid, path: $path}) "
                        + "OPTIONAL MATCH (f)-[:CONTAINS*0..]->(child) "
                        + "RETURN count(DISTINCT f) + count(DISTINCT child) AS c",
                Map.of("pid", projectId, "path", path));
        long total = count.isEmpty() ? 0L : asLong(count.get(0).get("c"));
        if (total == 0) return 0;
        queries.raw(
                "MATCH (f:File {projectId: $pid, path: $path}) "
                        + "OPTIONAL MATCH (f)-[:CONTAINS*0..]->(child) "
                        + "DETACH DELETE f, child",
                Map.of("pid", projectId, "path", path));
        return (int) total;
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    @Override
    public void close() { client.close(); }
}
