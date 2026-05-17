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

    /**
     * Volatile so the runtime project-switch path ({@link #swapToProject}) can atomically
     * point the store at a different Kuzu DB while concurrent queries are in flight. Each
     * query method reads the current reference once at the top and uses it for the duration
     * of the call — a swap landing mid-method completes the older query against the older
     * DB, which is the desired snapshot semantic.
     */
    private volatile EmbeddedKuzu kuzu;
    private volatile String displayUri;
    /** Guards {@link #swapToProject} so two concurrent switches don't both open new DBs. */
    private final Object swapLock = new Object();

    public KuzuGraphStore(EmbeddedKuzu kuzu) {
        this.kuzu = kuzu;
        this.displayUri = "kuzu://" + kuzu.dbPath();
    }

    public EmbeddedKuzu kuzu() { return kuzu; }

    @Override
    public void swapToProject(String projectId) {
        synchronized (swapLock) {
            // Resolve the target DB path the same way CvectorRestConfig does on initial boot
            // so existing-on-disk databases get reused (and freshly-created ones land in the
            // standard location). The new EmbeddedKuzu opens, schema-bootstraps, then we
            // flip the reference. Old DB is closed last so any concurrent query finishes
            // against its snapshot before the file handle goes away.
            java.nio.file.Path newPath = EmbeddedKuzu.defaultDbPath(projectId);
            try {
                EmbeddedKuzu next = new EmbeddedKuzu(newPath);
                new KuzuSchemaBootstrap(next).bootstrap();
                EmbeddedKuzu previous = this.kuzu;
                this.kuzu = next;
                this.displayUri = "kuzu://" + next.dbPath();
                // Best-effort close of the previous handle. If a request is still draining
                // results from the old DB it'll fail cleanly; the request layer surfaces that
                // as a 500, which the dashboard's auto-retry covers.
                try { previous.close(); } catch (RuntimeException ignored) { /* nothing actionable */ }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(
                        "failed to swap embedded kuzu to project " + projectId + " at " + newPath, e);
            }
        }
    }

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
        // Scoped to the requested project. Earlier this returned cross-project totals because
        // the shared-DB workspace puts every project's nodes in the same Kuzu directory; the
        // missing filter made cv_stats / cv_health show "47k nodes" when only some belonged to
        // the caller's project, and made cv_remove_project look like it had silently no-op'd
        // (counts didn't drop because they were never project-scoped in the first place).
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid RETURN n.label AS label, count(n) AS c ORDER BY label",
                Map.of("pid", projectId));
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
        // Project-scoped via both endpoints. Edges live in their own REL tables, so we have to
        // filter on the node ends rather than on the edge itself. Either endpoint matching is
        // enough in principle (every edge has both ends in the same project by construction),
        // but predicating both also rules out any cross-project edge that a buggy importer may
        // have created.
        for (String type : KuzuSchemaBootstrap.EDGE_TYPES) {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (a:Node)-[r:" + type + "]->(b:Node) "
                            + "WHERE a.projectId = $pid AND b.projectId = $pid "
                            + "RETURN count(r) AS c",
                    Map.of("pid", projectId));
            long count = rows.isEmpty() ? 0L : asLong(rows.get(0).get("c"));
            if (count > 0) out.put(type, count);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> schemaConnectivity(String projectId) {
        // One query per REL table. Kuzu's binder doesn't support a free-form edge-type
        // variable, so we loop. Each query is a small aggregation; with ~14 edge types
        // and the projectId filter pushing the row count down further, total wall time
        // stays comfortably under a second even on large graphs.
        List<Map<String, Object>> out = new ArrayList<>();
        for (String type : KuzuSchemaBootstrap.EDGE_TYPES) {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (a:Node)-[r:" + type + "]->(b:Node) "
                            + "WHERE a.projectId = $pid AND b.projectId = $pid "
                            + "RETURN a.label AS fromLabel, b.label AS toLabel, count(r) AS edgeCount",
                    Map.of("pid", projectId)
            );
            for (Map<String, Object> r : rows) {
                long c = asLong(r.get("edgeCount"));
                if (c <= 0) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from", String.valueOf(r.get("fromLabel")));
                row.put("to", String.valueOf(r.get("toLabel")));
                row.put("type", type);
                row.put("count", c);
                out.add(row);
            }
        }
        // Stable order: heaviest connections first, then alphabetic on (from,to,type) for ties.
        out.sort((a, b) -> {
            int byCount = Long.compare(asLong(b.get("count")), asLong(a.get("count")));
            if (byCount != 0) return byCount;
            int byFrom = ((String) a.get("from")).compareTo((String) b.get("from"));
            if (byFrom != 0) return byFrom;
            int byTo = ((String) a.get("to")).compareTo((String) b.get("to"));
            if (byTo != 0) return byTo;
            return ((String) a.get("type")).compareTo((String) b.get("type"));
        });
        return out;
    }

    @Override
    public List<Map<String, Object>> findSymbol(String projectId, String symbol) {
        // Four candidate predicates, scoped to the caller's project:
        //   * exact fqName                        — full match
        //   * fqName ENDS WITH ".$symbol"         — bare name match anywhere in the FQ chain
        //   * name = $symbol                      — simple name on the node itself
        //   * fqName STARTS WITH "$symbol("       — class.method (no signature) matches the
        //                                           Method whose fqName is class.method(args)
        // The STARTS WITH branch is what lets cv_explain("io.foo.Bar.baz") resolve when the
        // graph stores it as "io.foo.Bar.baz(int, String)". Kuzu doesn't support inline string
        // concatenation in predicates, so the prefix is pre-built and passed as a param.
        // projectId predicate is mandatory in shared-DB mode — without it, every project's
        // duplicate-named symbols collide in the result set.
        return kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid "
                        + "AND (n.fqName = $sym OR n.fqName ENDS WITH $suffix "
                        + "     OR n.name = $sym OR n.fqName STARTS WITH $prefix) "
                        + "RETURN n.label AS label, n.fqName AS fqName, n.name AS name, "
                        + "n.id AS id, n.startLine AS startLine, n.fileId AS fileId LIMIT 25",
                Map.of("pid", projectId, "sym", symbol, "suffix", "." + symbol, "prefix", symbol + "("));
    }

    @Override
    public List<Map<String, Object>> searchByName(String projectId, String query, String label, int limit) {
        // Build an AND-chained CONTAINS predicate for each * segment, against lower(n.name) and
        // lower(n.fqName). For a query like "foo*bar" we want results containing both substrings.
        String[] segments = query.toLowerCase().split("\\*");
        StringBuilder predicate = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lim", limit);
        params.put("pid", projectId);
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
        // projectId predicate first so cv_search doesn't bleed cross-project results.
        StringBuilder cypher = new StringBuilder("MATCH (n:Node) WHERE n.projectId = $pid AND ").append(predicate);
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
        // Must filter on projectId — in a shared-DB workspace there are multiple Project nodes
        // and without the predicate this returned the first one Kuzu happened to find, which
        // is why cv_health.lastScanCommit appeared frozen at an old commit even after a fresh
        // scan landed on a different Project node (the right one).
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (p:Node) WHERE p.label = 'Project' AND p.projectId = $pid "
                        + "RETURN p.lastScanCommit AS lastScanCommit, p.rootPath AS rootPath LIMIT 1",
                Map.of("pid", projectId));
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
                "MATCH (n:Node) WHERE n.projectId = $pid "
                        + "AND n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt > timestamp($cutoff) "
                        + "RETURN n.label AS label, n.fqName AS fqName, "
                        + "cast(n.lastIngestedAt AS STRING) AS lastIngestedAt "
                        + "ORDER BY n.lastIngestedAt DESC LIMIT $lim",
                Map.of("pid", projectId, "cutoff", cutoff, "lim", limit));
    }

    @Override
    public List<Map<String, Object>> mavenDependencies(String projectId) {
        return kuzu.read(
                "MATCH (d:Node) WHERE d.label = 'MavenDependency' AND d.projectId = $pid "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, "
                        + "d.version AS version, d.scope AS scope "
                        + "ORDER BY d.groupId, d.artifactId",
                Map.of("pid", projectId));
    }

    @Override
    public List<Map<String, Object>> fileInventory(String projectId) {
        return kuzu.read(
                "MATCH (f:Node) WHERE f.label = 'File' AND f.projectId = $pid "
                        + "OPTIONAL MATCH (f)-[:CONTAINS]->(m:Node) "
                        + "WITH f, count(m) AS methodCount "
                        + "RETURN f.path AS path, f.language AS language, f.lineCount AS lineCount, "
                        + "       methodCount, cast(f.lastIngestedAt AS STRING) AS lastIngestedAt "
                        + "ORDER BY f.path",
                Map.of("pid", projectId));
    }

    @Override
    public Map<String, List<Map<String, Object>>> healthRollup(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("godFiles", kuzu.read(
                "MATCH (f:Node)-[:CONTAINS]->(c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE f.label = 'File' AND c.label = 'Class' AND m.label = 'Method' "
                        + "AND f.projectId = $pid "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 "
                        + "RETURN f.path AS path, methods ORDER BY methods DESC LIMIT 20", p));
        out.put("godClasses", kuzu.read(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' "
                        + "AND c.projectId = $pid "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 "
                        + "RETURN c.fqName AS fqName, methods ORDER BY methods DESC LIMIT 20", p));
        out.put("longMethods", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                        + "AND (m.endLine - m.startLine) >= 80 "
                        + "RETURN m.fqName AS fqName, (m.endLine - m.startLine) AS lines "
                        + "ORDER BY lines DESC LIMIT 20", p));
        // Dead code: methods with no incoming CALLS, excluding tests, mains, constructors,
        // and only flagging private/package visibility. Kuzu doesn't support EXISTS subqueries,
        // so we OPTIONAL MATCH the incoming edges and filter on count = 0.
        out.put("deadCode", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND coalesce(m.isTest, false) = false "
                        + "AND m.name <> 'main' "
                        + "AND coalesce(m.isConstructor, false) = false "
                        + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                        + "OPTIONAL MATCH ()-[r:CALLS]->(m) "
                        + "WITH m, count(r) AS incoming "
                        + "WHERE incoming = 0 "
                        + "RETURN m.fqName AS fqName ORDER BY m.fqName LIMIT 20", p));
        return out;
    }

    @Override
    public Map<String, Object> guardSummary(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        long godFiles = countOne(
                "MATCH (f:Node)-[:CONTAINS]->(c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE f.label = 'File' AND c.label = 'Class' AND m.label = 'Method' "
                        + "AND f.projectId = $pid "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 RETURN count(f) AS c", p);
        long godClasses = countOne(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' AND c.projectId = $pid "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 RETURN count(c) AS c", p);
        long longMethods = countOne(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND (m.endLine - m.startLine) >= 80 RETURN count(m) AS c", p);
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

    private long countOne(String cypher, Map<String, Object> params) {
        List<Map<String, Object>> rows = kuzu.read(cypher, params);
        if (rows.isEmpty()) return 0L;
        return asLong(rows.get(0).get("c"));
    }

    @Override
    public Map<String, List<Map<String, Object>>> infrastructureSummary(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("apiEndpoints", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'ApiEndpoint' AND e.projectId = $pid "
                        + "RETURN e.httpMethod AS httpMethod, e.path AS path, e.framework AS framework "
                        + "ORDER BY path", p));
        out.put("queueListeners", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS fqName", p));
        out.put("scheduledJobs", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND coalesce(m.isScheduled, false) = true "
                        + "RETURN m.fqName AS fqName", p));
        out.put("configKeys", kuzu.read(
                "MATCH (c:Node) WHERE c.label = 'ConfigKey' AND c.projectId = $pid "
                        + "RETURN c.fqName AS fqName ORDER BY c.fqName", p));
        out.put("envVars", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'EnvVar' AND e.projectId = $pid "
                        + "RETURN e.fqName AS fqName, e.value AS value ORDER BY e.fqName", p));
        out.put("containerImages", kuzu.read(
                "MATCH (i:Node) WHERE i.label = 'ContainerImage' AND i.projectId = $pid "
                        + "RETURN i.fqName AS fqName, i.repository AS repository, i.tag AS tag", p));
        out.put("containerPorts", kuzu.read(
                "MATCH (cp:Node) WHERE cp.label = 'ContainerPort' AND cp.projectId = $pid "
                        + "RETURN cp.port AS port, cp.protocol AS protocol", p));
        out.put("terraformResources", kuzu.read(
                "MATCH (r:Node) WHERE r.label = 'Resource' AND r.projectId = $pid "
                        + "RETURN r.fqName AS fqName, r.resourceType AS resourceType, r.provider AS provider", p));
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
                            + "AND e.projectId = $pid "
                            + "OPTIONAL MATCH (handler)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN e.httpMethod AS method, e.path AS path, handler.fqName AS handler, "
                            + "list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit, "pid", projectId)));
        }
        if ("main".equals(k) || "all".equals(k)) {
            out.put("main", kuzu.read(
                    "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                            + "AND m.name = 'main' "
                            + "AND coalesce(m.isStatic, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN m.fqName AS entry, list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit, "pid", projectId)));
        }
        if ("test".equals(k) || "all".equals(k)) {
            out.put("test", kuzu.read(
                    "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                            + "AND coalesce(m.isTest, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + d + "]->(c:Node) "
                            + "WHERE c.label = 'Method' "
                            + "RETURN m.fqName AS entry, list_slice(collect(DISTINCT c.fqName), 1, 50) AS reaches LIMIT $lim",
                    Map.of("lim", limit, "pid", projectId)));
        }
        return out;
    }

    @Override
    public Map<String, List<Map<String, Object>>> serviceLinks(String projectId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("outgoingHttp", readOutgoingHttp(projectId));
        out.put("outgoingMessaging", readOutgoingMessaging(projectId));
        out.put("incomingConsumers", readIncomingConsumers(projectId));
        out.put("restEndpoints", readRestEndpoints(projectId));
        out.put("tablesTouched", readTablesTouched(projectId));
        return out;
    }

    /**
     * Outgoing HTTP from two parser dialects:
     * <ul>
     *   <li>JVM: {@code Method -[CALLS]-> Method} where the callee is a Spring/Java HTTP client
     *       class. Captured by regex match on the callee fqName.</li>
     *   <li>JS/TS: {@code File -[CALLS_HTTP]-> HttpCall}, where the HttpCall node carries
     *       {@code httpMethod}, {@code path} (= url), and {@code framework} (axios/fetch/...)
     *       properties. Emitted when the TS parser sees a client-receiver call like
     *       {@code axios.post('url', body)}.</li>
     * </ul>
     */
    private List<Map<String, Object>> readOutgoingHttp(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        List<Map<String, Object>> jvm = kuzu.read(
                "MATCH (m:Node)-[:CALLS]->(callee:Node) "
                        + "WHERE m.label = 'Method' AND callee.label = 'Method' "
                        + "AND m.projectId = $pid "
                        + "AND regexp_matches(callee.fqName, '(?i).*(RestTemplate|WebClient|HttpClient|FeignClient|OkHttpClient).*') "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                        + "ORDER BY calls DESC LIMIT 50", p);
        List<Map<String, Object>> js = kuzu.read(
                "MATCH (f:Node)-[:CALLS_HTTP]->(h:Node) "
                        + "WHERE f.label = 'File' AND h.label = 'HttpCall' "
                        + "AND f.projectId = $pid "
                        + "RETURN f.path AS caller, h.httpMethod AS method, h.path AS target, "
                        + "h.framework AS client ORDER BY target", p);
        List<Map<String, Object>> all = new ArrayList<>(jvm.size() + js.size());
        all.addAll(jvm);
        all.addAll(js);
        return all;
    }

    private List<Map<String, Object>> readOutgoingMessaging(String projectId) {
        return kuzu.read(
                "MATCH (m:Node)-[:CALLS]->(callee:Node) "
                        + "WHERE m.label = 'Method' AND callee.label = 'Method' "
                        + "AND m.projectId = $pid "
                        + "AND regexp_matches(callee.fqName, '(?i).*(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|SqsTemplate|SnsTemplate).*') "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                        + "ORDER BY calls DESC LIMIT 50",
                Map.of("pid", projectId));
    }

    private List<Map<String, Object>> readIncomingConsumers(String projectId) {
        return kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS handler LIMIT 100",
                Map.of("pid", projectId));
    }

    /**
     * REST endpoints + their handlers. Endpoints come from {@code File -[EXPOSES]-> ApiEndpoint}
     * (every parser does this), and a Method handler comes from
     * {@code ApiEndpoint -[HANDLES]-> Method} (only some parsers do this). Querying via HANDLES
     * alone hides the 17+ Express/Vue endpoints that have no method link, and Kuzu's binder
     * rejects {@code OPTIONAL MATCH} chained from an outer MATCH here, so we read both
     * relationships independently and join in Java.
     */
    private List<Map<String, Object>> readRestEndpoints(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        List<Map<String, Object>> endpointRows = kuzu.read(
                "MATCH (f:Node)-[:EXPOSES]->(e:Node) "
                        + "WHERE f.label = 'File' AND e.label = 'ApiEndpoint' "
                        + "AND f.projectId = $pid "
                        + "RETURN e.fqName AS endpointKey, e.httpMethod AS method, e.path AS path, "
                        + "e.framework AS framework, f.path AS file ORDER BY path", p);
        List<Map<String, Object>> handlerRows = kuzu.read(
                "MATCH (e:Node)-[:HANDLES]->(m:Node) "
                        + "WHERE e.label = 'ApiEndpoint' AND m.label = 'Method' "
                        + "AND e.projectId = $pid "
                        + "RETURN e.fqName AS endpointKey, m.fqName AS handler", p);
        Map<Object, Object> handlerByEndpoint = new java.util.HashMap<>();
        for (Map<String, Object> r : handlerRows) handlerByEndpoint.put(r.get("endpointKey"), r.get("handler"));
        List<Map<String, Object>> endpoints = new ArrayList<>(endpointRows.size());
        for (Map<String, Object> r : endpointRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", r.get("method"));
            row.put("path", r.get("path"));
            row.put("framework", r.get("framework"));
            row.put("file", r.get("file"));
            row.put("handler", handlerByEndpoint.getOrDefault(r.get("endpointKey"), ""));
            endpoints.add(row);
        }
        return endpoints;
    }

    /**
     * Tables touched, tagged by access kind. Kuzu can't union {@code READS_TABLE | WRITES_TABLE}
     * in a single MATCH on the polymorphic Node table, so we issue one query per relationship
     * type and tag each result row with its {@code access} kind.
     */
    private List<Map<String, Object>> readTablesTouched(String projectId) {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String rel : List.of("READS_TABLE", "WRITES_TABLE")) {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (n:Node)-[r:" + rel + "]->(t:Node) "
                            + "WHERE t.label = 'Table' AND n.projectId = $pid "
                            + "RETURN t.name AS `table`, count(DISTINCT n) AS sources "
                            + "ORDER BY `table`",
                    Map.of("pid", projectId));
            for (Map<String, Object> r : rows) {
                Map<String, Object> tagged = new LinkedHashMap<>(r);
                tagged.put("access", rel);
                tables.add(tagged);
            }
        }
        return tables;
    }

    @Override
    public Map<String, List<Map<String, Object>>> onboardSummary(String projectId) {
        Map<String, Object> p = Map.of("pid", projectId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("languages", kuzu.read(
                "MATCH (f:Node) WHERE f.label = 'File' AND f.projectId = $pid "
                        + "RETURN f.language AS language, count(f) AS files ORDER BY files DESC", p));
        out.put("topClasses", kuzu.read(
                "MATCH (c:Node)-[:CONTAINS]->(m:Node) "
                        + "WHERE c.label = 'Class' AND m.label = 'Method' AND c.projectId = $pid "
                        + "RETURN c.fqName AS fqName, count(m) AS methods ORDER BY methods DESC LIMIT 10", p));
        out.put("restEndpoints", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'ApiEndpoint' AND e.projectId = $pid "
                        + "RETURN e.httpMethod AS method, e.path AS path, e.framework AS framework ORDER BY path", p));
        // Two-step: list all tables, then fetch table→column counts, merge in Java. Kuzu's
        // binder doesn't keep the outer-MATCH variable in scope through an OPTIONAL MATCH +
        // WITH chain here, so we compose the result instead of one composite Cypher.
        List<Map<String, Object>> tableRows = kuzu.read(
                "MATCH (t:Node) WHERE t.label = 'Table' AND t.projectId = $pid "
                        + "RETURN t.name AS `table` ORDER BY t.name", p);
        List<Map<String, Object>> colCounts = kuzu.read(
                "MATCH (t:Node)-[:CONTAINS]->(c:Node) "
                        + "WHERE t.label = 'Table' AND c.label = 'Column' AND t.projectId = $pid "
                        + "RETURN t.name AS `table`, count(c) AS columns", p);
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
                "MATCH (k:Node) WHERE k.label = 'ConfigKey' AND k.projectId = $pid "
                        + "RETURN k.fqName AS key, k.value AS value ORDER BY k.fqName LIMIT 50", p));
        out.put("envVars", kuzu.read(
                "MATCH (e:Node) WHERE e.label = 'EnvVar' AND e.projectId = $pid "
                        + "RETURN e.name AS name, e.value AS value ORDER BY e.name", p));
        out.put("callGraphHubs", kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "AND NOT m.fqName STARTS WITH 'unresolved.' "
                        + "OPTIONAL MATCH (m)-[outR:CALLS]->() "
                        + "WITH m, count(outR) AS outDeg "
                        + "OPTIONAL MATCH ()-[inR:CALLS]->(m) "
                        + "WITH m, outDeg, count(inR) AS inDeg "
                        + "WHERE (outDeg + inDeg) > 0 "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, (outDeg + inDeg) AS total "
                        + "ORDER BY total DESC LIMIT 10", p));
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
        Map<String, Object> p = Map.of("pid", projectId);
        List<Map<String, Object>> methods = kuzu.read(
                "MATCH (m:Node) WHERE m.label = 'Method' AND m.projectId = $pid "
                        + "RETURN m.id AS id, m.fqName AS fqName", p);
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
                        + "AND a.projectId = $pid AND b.projectId = $pid "
                        + "RETURN a.id AS fromId, b.id AS toId", p);
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
    public Map<String, String> bulkFilesByPath(String projectId, List<String> paths) {
        Map<String, String> out = new LinkedHashMap<>();
        if (paths == null || paths.isEmpty()) return out;
        // Kuzu's Java SDK doesn't expose a clean LIST<STRING> param constructor — passing a
        // java.util.List binds as its toString() representation, which UNWIND rejects. So we
        // build an inline IN clause with each path single-quote-escaped. Path strings come
        // from git diff output and node graph paths — both controlled inputs — but we still
        // escape defensively in case a filename contains an apostrophe.
        StringBuilder inList = new StringBuilder("[");
        boolean first = true;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            if (!first) inList.append(",");
            first = false;
            inList.append("'").append(p.replace("'", "''")).append("'");
        }
        inList.append("]");
        if (first) return out; // all paths were blank
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (f:Node) WHERE f.label = 'File' AND f.path IN " + inList
                            + " RETURN f.path AS path, f.id AS id");
            for (Map<String, Object> r : rows) {
                Object p = r.get("path");
                Object id = r.get("id");
                if (p != null && id != null) out.put(p.toString(), id.toString());
            }
            return out;
        } catch (RuntimeException ignored) {
            return GraphStore.super.bulkFilesByPath(projectId, paths);
        }
    }

    @Override
    public Map<String, List<Map<String, Object>>> bulkContains(String projectId, List<String> fileIds, int limitPerFile) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (fileIds == null || fileIds.isEmpty()) return out;
        // Same inline-list shape as bulkFilesByPath; file ids are 16 hex chars (NodeKey hash)
        // so no escaping is required. One round trip vs N replaces the dominant cost of the
        // PR-impact endpoint on big PRs.
        StringBuilder inList = new StringBuilder("[");
        boolean first = true;
        for (String fid : fileIds) {
            if (fid == null || fid.isBlank()) continue;
            if (!first) inList.append(",");
            first = false;
            inList.append("'").append(fid.replace("'", "''")).append("'");
        }
        inList.append("]");
        if (first) return out;
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (f:Node)-[:CONTAINS]->(c:Node) WHERE f.id IN " + inList
                            + " RETURN f.id AS fileId, c.label AS label, c.fqName AS fqName, c.id AS id");
            for (String fid : fileIds) out.put(fid, new ArrayList<>());
            for (Map<String, Object> r : rows) {
                Object fid = r.get("fileId");
                if (fid == null) continue;
                List<Map<String, Object>> bucket = out.get(fid.toString());
                if (bucket == null) continue;
                if (bucket.size() >= limitPerFile) continue;
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("label", r.get("label"));
                child.put("fqName", r.get("fqName"));
                child.put("id", r.get("id"));
                bucket.add(child);
            }
            return out;
        } catch (RuntimeException ignored) {
            return GraphStore.super.bulkContains(projectId, fileIds, limitPerFile);
        }
    }

    @Override
    public Map<String, Long> bulkCallerCounts(String projectId, List<String> ids) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        String inList = quoteListOrEmpty(ids);
        if (inList == null) return out;
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (caller:Node)-[:CALLS]->(callee:Node) "
                            + "WHERE callee.id IN " + inList + " AND caller.label = 'Method' "
                            + "RETURN callee.id AS targetId, count(caller) AS cnt");
            for (String id : ids) out.put(id, 0L);
            for (Map<String, Object> r : rows) {
                Object tid = r.get("targetId");
                if (tid == null) continue;
                long cnt = (r.get("cnt") instanceof Number n) ? n.longValue() : 0L;
                out.put(tid.toString(), cnt);
            }
            return out;
        } catch (RuntimeException ignored) {
            return GraphStore.super.bulkCallerCounts(projectId, ids);
        }
    }

    @Override
    public Map<String, Long> bulkCalleeCounts(String projectId, List<String> ids) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        String inList = quoteListOrEmpty(ids);
        if (inList == null) return out;
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH (src:Node)-[:CALLS]->(dst:Node) "
                            + "WHERE src.id IN " + inList + " "
                            + "RETURN src.id AS sourceId, count(dst) AS cnt");
            for (String id : ids) out.put(id, 0L);
            for (Map<String, Object> r : rows) {
                Object sid = r.get("sourceId");
                if (sid == null) continue;
                long cnt = (r.get("cnt") instanceof Number n) ? n.longValue() : 0L;
                out.put(sid.toString(), cnt);
            }
            return out;
        } catch (RuntimeException ignored) {
            return GraphStore.super.bulkCalleeCounts(projectId, ids);
        }
    }

    @Override
    public Map<String, java.util.Set<String>> bulkImpactedIds(String projectId, List<String> ids, int depth) {
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        int safeDepth = Math.max(1, Math.min(depth, 8));
        String inList = quoteListOrEmpty(ids);
        if (inList == null) return out;
        for (String id : ids) if (id != null && !id.isBlank()) out.put(id, new java.util.LinkedHashSet<>());
        // Two queries (CALLS and REFERENCES) instead of 2 × N. Each returns (source, impacted)
        // pairs; we group in Java to keep the Cypher trivial.
        for (String rel : List.of("CALLS", "REFERENCES")) {
            try {
                List<Map<String, Object>> rows = kuzu.read(
                        "MATCH (start:Node)-[:" + rel + "*1.." + safeDepth + "]->(impacted:Node) "
                                + "WHERE start.id IN " + inList + " "
                                + "RETURN DISTINCT start.id AS source, impacted.id AS impacted");
                for (Map<String, Object> r : rows) {
                    Object src = r.get("source");
                    Object imp = r.get("impacted");
                    if (src == null || imp == null) continue;
                    java.util.Set<String> bucket = out.get(src.toString());
                    if (bucket != null) bucket.add(imp.toString());
                }
            } catch (RuntimeException ignored) {
                // Some Kuzu builds reject the variable-length path on REFERENCES edges that
                // don't exist in the project — skip that branch instead of falling back to
                // per-id loop (which would re-hit the same issue).
            }
        }
        return out;
    }

    /** Build a Cypher list literal from {@code ids}, or null if the list contains no non-blank entries. */
    private static String quoteListOrEmpty(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("'").append(id.replace("'", "''")).append("'");
        }
        sb.append("]");
        return first ? null : sb.toString();
    }

    @Override
    public List<Map<String, Object>> findDuplicates(String projectId, int minOccurrences) {
        int min = Math.max(2, minOccurrences);
        // Single query: group Methods by (name, paramCount, returnType, lineCount-bucket) and
        // emit groups with count >= min. lineCount-bucket rounds to the nearest 5 so methods
        // of "about the same size" group together even if one has a couple of extra lines.
        // Exclude constructors (`<init>`) — they're trivially "duplicated" across every class.
        List<Map<String, Object>> rows;
        try {
            rows = kuzu.read(
                    "MATCH (m:Node) WHERE m.label = 'Method' "
                            + "AND m.name IS NOT NULL AND m.name <> '<init>' "
                            + "AND m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                            + "AND (m.endLine - m.startLine) >= 5 "  // ignore one-liners; they're often legitimately repeated
                            + "WITH m, m.name AS name, coalesce(m.paramCount, 0) AS pc, "
                            + "     coalesce(m.returnType, '') AS rt, "
                            + "     ((m.endLine - m.startLine) / 5) * 5 AS lineBucket "
                            + "WITH name, pc, rt, lineBucket, "
                            + "     count(m) AS occurrences, collect(m.fqName) AS fqNames "
                            + "WHERE occurrences >= $min "
                            + "RETURN name, pc AS paramCount, rt AS returnType, lineBucket, "
                            + "       occurrences, fqNames "
                            + "ORDER BY occurrences DESC, name LIMIT 100",
                    Map.of("min", min));
        } catch (RuntimeException ignored) {
            return List.of();
        }
        // Flatten Kuzu's serialised list strings into proper Java lists if needed; the unwrap
        // pass already handles LIST<STRING> but the embedded-shape needs a per-row touch.
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.get("name"));
            row.put("paramCount", r.get("paramCount"));
            row.put("returnType", r.get("returnType"));
            row.put("lineBucket", r.get("lineBucket"));
            row.put("occurrences", r.get("occurrences"));
            Object fq = r.get("fqNames");
            row.put("members", fq == null ? List.of() : fq);
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> nodeById(String projectId, String id) {
        if (id == null) return Map.of();
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node {id: $id}) "
                        + "RETURN n.label AS label, n.fqName AS fqName, n.name AS name LIMIT 1",
                Map.of("id", id));
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.get(0));
    }

    @Override
    public Map<String, Object> shortestPath(String projectId, String fromId, String toId, int maxDepth) {
        if (fromId == null || toId == null) return emptyPathResult();
        int safeDepth = Math.max(1, Math.min(maxDepth, 12));
        if (fromId.equals(toId)) return zeroLengthPath();

        // Two-phase: ask Kuzu for the shortest hop count via min(length(p)), then reconstruct
        // the ordered path with a depth-bounded BFS in Java. The Java step is unavoidable
        // because Kuzu path values don't round-trip through EmbeddedKuzu.unwrap into a stable
        // JSON shape; the Cypher pre-flight at least lets us cap the BFS depth tightly.
        Integer depthHit = probeShortestDepth(fromId, toId, safeDepth);
        if (depthHit == null) return emptyPathResult();

        List<String> orderedIds = bfsPath(projectId, fromId, toId, depthHit);
        if (orderedIds == null) return emptyPathResult();
        return buildPathResult(projectId, orderedIds);
    }

    private static Map<String, Object> emptyPathResult() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("found", false);
        empty.put("depth", -1);
        empty.put("nodes", List.of());
        empty.put("edges", List.of());
        return empty;
    }

    private static Map<String, Object> zeroLengthPath() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("depth", 0);
        result.put("nodes", List.of());
        result.put("edges", List.of());
        return result;
    }

    /** Returns the shortest path length in hops, or null when no path within {@code safeDepth} exists. */
    private Integer probeShortestDepth(String fromId, String toId, int safeDepth) {
        try {
            List<Map<String, Object>> rows = kuzu.read(
                    "MATCH p = (a:Node {id: $from})-[:CALLS*1.." + safeDepth + "]->(b:Node {id: $to}) "
                            + "RETURN min(length(p)) AS d LIMIT 1",
                    Map.of("from", fromId, "to", toId));
            if (rows.isEmpty()) return null;
            Object d = rows.get(0).get("d");
            return d instanceof Number n ? n.intValue() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Depth-bounded BFS via callees; returns the ordered id list from from→to, or null if unreachable. */
    private List<String> bfsPath(String projectId, String fromId, String toId, int depthHit) {
        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, Integer> depth = new java.util.HashMap<>();
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>();
        depth.put(fromId, 0);
        frontier.add(fromId);
        String found = null;
        outer:
        while (!frontier.isEmpty()) {
            String cur = frontier.poll();
            int d = depth.get(cur);
            if (d >= depthHit) continue;
            for (Map<String, Object> nb : callees(projectId, cur)) {
                Object idObj = nb.get("id");
                if (idObj == null) continue;
                String nbId = idObj.toString();
                if (depth.containsKey(nbId)) continue;
                depth.put(nbId, d + 1);
                parent.put(nbId, cur);
                if (nbId.equals(toId)) { found = nbId; break outer; }
                frontier.add(nbId);
            }
        }
        if (found == null) return null;
        java.util.LinkedList<String> ids = new java.util.LinkedList<>();
        for (String n = found; n != null; n = parent.get(n)) {
            ids.addFirst(n);
            if (n.equals(fromId)) break;
        }
        return ids;
    }

    /** Builds the user-facing {nodes, edges, depth, found} response from an ordered id chain. */
    private Map<String, Object> buildPathResult(String projectId, List<String> ids) {
        List<Map<String, Object>> nodes = new ArrayList<>(ids.size());
        for (String id : ids) {
            Map<String, Object> meta = nodeById(projectId, id);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("label", meta.getOrDefault("label", ""));
            n.put("name", meta.getOrDefault("name", ""));
            n.put("fqName", meta.getOrDefault("fqName", ""));
            nodes.add(n);
        }
        List<Map<String, Object>> edges = new ArrayList<>(Math.max(0, ids.size() - 1));
        for (int i = 0; i + 1 < ids.size(); i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", ids.get(i));
            e.put("to", ids.get(i + 1));
            e.put("type", "CALLS");
            edges.add(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("depth", ids.size() - 1);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    @Override
    public Map<String, List<Map<String, Object>>> dbImpact(String projectId, String table, String column) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (table == null || table.isBlank()) {
            out.put("readers", List.of());
            out.put("writers", List.of());
            return out;
        }
        // Readers / writers: methods that have READS_TABLE / WRITES_TABLE → table-by-name.
        // Kuzu's binder gets confused by multi-variable WHERE clauses on a single polymorphic
        // Node table — the empirically-working shape is a simple MATCH with one variable
        // filtered inline, plus a downstream WHERE on the *other* variable. The DISTINCT +
        // dedup happens in Java to sidestep another binder quirk where DISTINCT projection
        // doesn't pick up imported aliases. Param key is $tbl (not $table) because Kuzu's
        // parser treats TABLE as a reserved word.
        List<Map<String, Object>> readers = dedupByFqName(kuzu.read(
                "MATCH (m:Node)-[:READS_TABLE]->(t:Node {label: 'Table', name: $tbl}) "
                        + "WHERE m.label = 'Method' "
                        + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line LIMIT 500",
                Map.of("tbl", table)));
        List<Map<String, Object>> writers = dedupByFqName(kuzu.read(
                "MATCH (m:Node)-[:WRITES_TABLE]->(t:Node {label: 'Table', name: $tbl}) "
                        + "WHERE m.label = 'Method' "
                        + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line LIMIT 500",
                Map.of("tbl", table)));
        if (column != null && !column.isBlank()) {
            // Narrow by column. READS_COLUMN / WRITES_COLUMN aren't always in the schema
            // (older snapshots, parsers that don't emit them), so wrap each branch and fall
            // back to the table-level set when the rel type isn't recognised.
            List<Map<String, Object>> colReaders = readColumnUsage(table, column, "READS_COLUMN");
            List<Map<String, Object>> colWriters = readColumnUsage(table, column, "WRITES_COLUMN");
            // Intersect (column-scope is a refinement of the table set). Keep entries that
            // appear in BOTH the table-level set and the column-level set so a method that
            // only touches a different column on the same table doesn't bleed through.
            if (colReaders != null) readers = intersectByFqName(readers, colReaders);
            if (colWriters != null) writers = intersectByFqName(writers, colWriters);
        }
        out.put("readers", readers);
        out.put("writers", writers);
        return out;
    }

    private static List<Map<String, Object>> intersectByFqName(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        java.util.Set<Object> keysB = new java.util.HashSet<>();
        for (Map<String, Object> r : b) keysB.add(r.get("fqName"));
        List<Map<String, Object>> out = new ArrayList<>(Math.min(a.size(), b.size()));
        for (Map<String, Object> r : a) if (keysB.contains(r.get("fqName"))) out.add(r);
        return out;
    }

    /** Returns column-scoped usage or null if the rel type isn't in the current schema. */
    private List<Map<String, Object>> readColumnUsage(String table, String column, String rel) {
        try {
            return dedupByFqName(kuzu.read(
                    "MATCH (m:Node)-[:" + rel + "]->(c:Node {label: 'Column', name: $col})"
                            + "<-[:CONTAINS]-(t:Node {label: 'Table', name: $tbl}) "
                            + "WHERE m.label = 'Method' "
                            + "RETURN m.fqName AS fqName, m.fileId AS fileId, m.startLine AS line LIMIT 500",
                    Map.of("tbl", table, "col", column)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Map<String, Object>> dedupByFqName(List<Map<String, Object>> rows) {
        Map<Object, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = r.get("fqName");
            if (k != null) seen.putIfAbsent(k, r);
        }
        List<Map<String, Object>> out = new ArrayList<>(seen.values());
        out.sort((a, b) -> String.valueOf(a.get("fqName")).compareTo(String.valueOf(b.get("fqName"))));
        return out;
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
        // Scope to projectId so shared-DB workspaces with project-A data already present
        // still let project-B report empty (and qualify for bulk-load mode on its first scan).
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid RETURN count(n) AS c",
                Map.of("pid", projectId));
        return rows.isEmpty() || asLong(rows.get(0).get("c")) == 0;
    }

    @Override
    public int deleteProjectSubtree(String projectId) {
        // Surgical "remove this project's rows from the (possibly shared) Kuzu DB" used by
        // cv_remove_project and embedded wipe. Counts first so we can report what got
        // removed; then DETACH DELETE in a single statement.
        List<Map<String, Object>> count = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid RETURN count(n) AS c",
                Map.of("pid", projectId));
        long total = count.isEmpty() ? 0L : asLong(count.get(0).get("c"));
        if (total == 0) return 0;
        kuzu.write("MATCH (n:Node) WHERE n.projectId = $pid DETACH DELETE n",
                Map.of("pid", projectId));
        return (int) total;
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
