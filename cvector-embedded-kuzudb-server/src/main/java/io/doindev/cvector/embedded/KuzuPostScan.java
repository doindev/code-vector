package io.doindev.cvector.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-scan reconciliation passes for the embedded Kuzu store. These mirror the routines in
 * {@code ScanCommand} that run against Neo4j after the parser walk, but use Kuzu-compatible
 * Cypher. The Neo4j versions lean on constructs Kuzu doesn't implement:
 * <ul>
 *   <li>{@code SET r += oldProps} for whole-map writes — Kuzu requires explicit columns.</li>
 *   <li>{@code EXISTS &#123; MATCH ... &#125;} subqueries — Kuzu wants OPTIONAL MATCH + IS NULL.</li>
 *   <li>{@code properties(r)} as a map projection — Kuzu wants per-column reads.</li>
 * </ul>
 * We split each pass into "read candidates / write fixups in Java" so the Cypher stays in
 * Kuzu's supported subset. The list of placeholders / endpoints is small (hundreds at most),
 * so the extra round-trips don't dominate.
 */
public final class KuzuPostScan {

    private static final Logger log = LoggerFactory.getLogger(KuzuPostScan.class);

    /** Node labels considered "scan-managed". Project + git/file metadata stays around between scans. */
    private static final List<String> STALE_CANDIDATE_LABELS = List.of(
            "Method", "Class", "Field", "File", "ApiEndpoint",
            "ConfigKey", "EnvVar", "MavenDependency", "Table", "Column",
            "CssClass", "DesignToken", "CssMixin"
    );

    private KuzuPostScan() {}

    /**
     * Resolve {@code unresolved.<name>:<arity>} placeholder Method nodes left by the Java parser
     * to concrete project methods. For each placeholder that has exactly one same-name/same-arity
     * candidate, rewire every {@code CALLS} edge pointing at the placeholder to the candidate,
     * preserving the original edge properties, and drop the placeholder. Returns the number of
     * placeholders rewired.
     */
    public static int resolveUnresolvedCalls(EmbeddedKuzu kuzu, String projectId) {
        List<Map<String, Object>> placeholders = kuzu.read(
                "MATCH (p:Node) "
                        + "WHERE p.projectId = $pid AND p.label = 'Method' "
                        + "AND p.fqName STARTS WITH 'unresolved.' AND p.fqName CONTAINS ':' "
                        + "RETURN p.id AS id, p.name AS name, p.fqName AS fqName",
                Map.of("pid", projectId));
        if (placeholders.isEmpty()) return 0;

        // Batch step 1: collect all (name, arity) combinations the placeholders need to resolve.
        java.util.Set<String> wantedNames = new java.util.HashSet<>();
        java.util.List<Placeholder> phs = new java.util.ArrayList<>(placeholders.size());
        for (Map<String, Object> row : placeholders) {
            String id = (String) row.get("id");
            String name = (String) row.get("name");
            long arity = parseArity((String) row.get("fqName"));
            if (id == null || name == null || arity < 0) continue;
            phs.add(new Placeholder(id, name, arity));
            wantedNames.add(name);
        }
        if (phs.isEmpty()) return 0;

        // Bulk candidate fetch: one query for every method whose name appears in any placeholder.
        // List-of-strings parameter binding doesn't work reliably with Kuzu's generic Value, so
        // we inline the names as a literal list (parser-emitted names are simple identifiers but
        // we still escape single quotes as defence-in-depth).
        StringBuilder names = new StringBuilder();
        boolean first = true;
        for (String n : wantedNames) {
            if (!first) names.append(", ");
            first = false;
            names.append('\'').append(n.replace("'", "\\'")).append('\'');
        }
        List<Map<String, Object>> candidates = kuzu.read(
                "MATCH (c:Node) "
                        + "WHERE c.projectId = $pid AND c.label = 'Method' "
                        + "AND c.name IN [" + names + "] "
                        + "AND NOT c.fqName STARTS WITH 'unresolved.' "
                        + "RETURN c.id AS id, c.name AS name, c.paramCount AS paramCount",
                Map.of("pid", projectId));

        // Group: (name, arity) → list of candidate ids
        java.util.Map<String, List<String>> byKey = new java.util.HashMap<>(candidates.size() * 2);
        for (Map<String, Object> c : candidates) {
            String key = c.get("name") + "/" + asLong(c.get("paramCount"));
            byKey.computeIfAbsent(key, k -> new java.util.ArrayList<>(2)).add((String) c.get("id"));
        }

        // Pick placeholders with exactly one resolved candidate.
        java.util.List<String[]> rewireMap = new java.util.ArrayList<>(); // [placeholderId, candidateId]
        for (Placeholder p : phs) {
            List<String> cands = byKey.get(p.name + "/" + p.arity);
            if (cands == null || cands.size() != 1) continue;
            rewireMap.add(new String[]{p.id, cands.get(0)});
        }

        // Rewire each matched placeholder. The per-placeholder caller fetch + edge writes are
        // still serial Cypher round-trips, but inside one transaction so commit overhead is
        // amortised.
        int rewired = rewireAll(kuzu, rewireMap);
        dropOrphanPlaceholders(kuzu, projectId);
        return rewired;
    }

    private record Placeholder(String id, String name, long arity) {}

    /** Rewire all placeholders inside a single Kuzu transaction. */
    private static int rewireAll(EmbeddedKuzu kuzu, List<String[]> rewireMap) {
        if (rewireMap.isEmpty()) return 0;
        int rewired = 0;
        kuzu.write("BEGIN TRANSACTION", Map.of());
        try {
            for (String[] pair : rewireMap) {
                if (rewireCallers(kuzu, pair[0], pair[1])) rewired++;
            }
            kuzu.write("COMMIT", Map.of());
        } catch (RuntimeException e) {
            try { kuzu.write("ROLLBACK", Map.of()); } catch (RuntimeException ignored) { /* roll-forward */ }
            throw e;
        }
        return rewired;
    }

    private static long parseArity(String fqName) {
        if (fqName == null) return -1;
        int colon = fqName.lastIndexOf(':');
        if (colon < 0 || colon == fqName.length() - 1) return -1;
        try { return Long.parseLong(fqName.substring(colon + 1)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static boolean rewireCallers(EmbeddedKuzu kuzu, String placeholderId, String candidateId) {
        // Read every incoming CALLS edge with its column-by-column properties (Kuzu doesn't expose
        // a properties() map projection like Neo4j does).
        List<Map<String, Object>> callers = kuzu.read(
                "MATCH (caller)-[r:CALLS]->(p:Node {id: $pid}) "
                        + "RETURN caller.id AS callerId, "
                        + "       r.confidence AS confidence, "
                        + "       r.callSiteLine AS callSiteLine, "
                        + "       r.kind AS kind, "
                        + "       r.via AS via, "
                        + "       r.viaMethodReference AS viaMethodReference, "
                        + "       r.ambiguous AS ambiguous",
                Map.of("pid", placeholderId));
        if (callers.isEmpty()) return false;

        for (Map<String, Object> c : callers) {
            String callerId = (String) c.get("callerId");
            double newConfidence = numericConfidence(c.get("confidence")) + 0.2;

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("callerId", callerId);
            params.put("candidateId", candidateId);
            params.put("confidence", newConfidence);
            params.put("callSiteLine", c.get("callSiteLine"));
            params.put("kind", c.get("kind"));
            params.put("via", c.get("via"));
            params.put("viaMethodReference", c.get("viaMethodReference"));
            params.put("ambiguous", c.get("ambiguous"));
            // Merge a CALLS edge to the resolved candidate, carrying forward the original
            // properties (bumped confidence as the only delta).
            kuzu.write(
                    "MATCH (caller:Node {id: $callerId}), (resolved:Node {id: $candidateId}) "
                            + "MERGE (caller)-[r:CALLS]->(resolved) "
                            + "SET r.confidence = $confidence, "
                            + "    r.callSiteLine = $callSiteLine, "
                            + "    r.kind = $kind, "
                            + "    r.via = $via, "
                            + "    r.viaMethodReference = $viaMethodReference, "
                            + "    r.ambiguous = $ambiguous",
                    params);
        }

        // Drop the old edges. Kuzu supports DELETE on relationships matched against a pattern.
        kuzu.write(
                "MATCH (caller)-[r:CALLS]->(p:Node {id: $pid}) DELETE r",
                Map.of("pid", placeholderId));
        return true;
    }

    private static double numericConfidence(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.5;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0.5; }
    }

    private static void dropOrphanPlaceholders(EmbeddedKuzu kuzu, String projectId) {
        // Materialise orphan ids first (DETACH DELETE inside an OPTIONAL MATCH chain would mutate
        // the rows we're iterating). Then delete in a single transaction so commit overhead is
        // amortised across all orphans — used to be ~hundreds of separate auto-commits.
        List<Map<String, Object>> orphans = kuzu.read(
                "MATCH (p:Node) "
                        + "WHERE p.projectId = $pid AND p.label = 'Method' "
                        + "AND p.fqName STARTS WITH 'unresolved.' "
                        + "OPTIONAL MATCH ()-[r:CALLS]->(p) "
                        + "WITH p, count(r) AS incoming "
                        + "WHERE incoming = 0 "
                        + "RETURN p.id AS id",
                Map.of("pid", projectId));
        if (orphans.isEmpty()) return;
        kuzu.write("BEGIN TRANSACTION", Map.of());
        try {
            for (Map<String, Object> row : orphans) {
                kuzu.write("MATCH (n:Node {id: $id}) DETACH DELETE n", Map.of("id", row.get("id")));
            }
            kuzu.write("COMMIT", Map.of());
        } catch (RuntimeException e) {
            try { kuzu.write("ROLLBACK", Map.of()); } catch (RuntimeException ignored) { /* roll-forward */ }
            throw e;
        }
    }

    /**
     * Resolve {@code ApiEndpoint} nodes carrying a {@code viewRef} (e.g. Django {@code views.user_detail})
     * that don't yet have a {@code HANDLES} edge. Falls through silently when no candidates exist.
     * Returns the number of endpoints linked.
     */
    public static int resolveDeferredHandlers(EmbeddedKuzu kuzu, String projectId) {
        List<Map<String, Object>> endpoints = kuzu.read(
                "MATCH (e:Node) "
                        + "WHERE e.projectId = $pid AND e.label = 'ApiEndpoint' AND e.viewRef IS NOT NULL "
                        + "OPTIONAL MATCH (e)-[h:HANDLES]->() "
                        + "WITH e, count(h) AS handlerCount "
                        + "WHERE handlerCount = 0 "
                        + "RETURN e.id AS id, e.viewRef AS viewRef",
                Map.of("pid", projectId));
        if (endpoints.isEmpty()) return 0;

        int linked = 0;
        for (Map<String, Object> e : endpoints) {
            String viewRef = (String) e.get("viewRef");
            if (viewRef == null || viewRef.isBlank()) continue;
            int dot = viewRef.lastIndexOf('.');
            String handlerName = dot < 0 ? viewRef : viewRef.substring(dot + 1);

            // Find methods whose simple name matches the last dotted segment.
            List<Map<String, Object>> handlers = kuzu.read(
                    "MATCH (m:Node) "
                            + "WHERE m.projectId = $pid AND m.label = 'Method' AND m.name = $name "
                            + "RETURN m.id AS id",
                    Map.of("pid", projectId, "name", handlerName));
            if (handlers.isEmpty()) continue;

            for (Map<String, Object> h : handlers) {
                kuzu.write(
                        "MATCH (e:Node {id: $eid}), (m:Node {id: $mid}) "
                                + "MERGE (e)-[:HANDLES]->(m)",
                        Map.of("eid", e.get("id"), "mid", h.get("id")));
            }
            linked++;
        }
        return linked;
    }

    /**
     * Delete scan-managed nodes whose {@code lastIngestedAt} is older than the cutoff (i.e. they
     * weren't touched during the current scan). Returns the number of nodes removed.
     */
    public static int cleanupStale(EmbeddedKuzu kuzu, String projectId, String cutoffIso) {
        List<String> labels = STALE_CANDIDATE_LABELS;
        StringBuilder labelList = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) labelList.append(", ");
            labelList.append('\'').append(labels.get(i)).append('\'');
        }
        String matchClause =
                "MATCH (n:Node) "
                        + "WHERE n.projectId = $pid "
                        + "AND n.label IN [" + labelList + "] "
                        + "AND n.lastIngestedAt IS NOT NULL "
                        + "AND n.lastIngestedAt < timestamp($cutoff) ";

        List<Map<String, Object>> rows = kuzu.read(
                matchClause + "RETURN count(n) AS c",
                Map.of("pid", projectId, "cutoff", cutoffIso));
        long count = rows.isEmpty() ? 0L : asLong(rows.get(0).get("c"));
        if (count == 0) return 0;

        // DETACH DELETE in a single statement — Kuzu handles fan-out across all REL tables.
        kuzu.write(matchClause + "DETACH DELETE n",
                Map.of("pid", projectId, "cutoff", cutoffIso));
        return (int) count;
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

}
