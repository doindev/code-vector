package io.doindev.cvector.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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

        // Rewire all matched placeholders in batched fashion: ONE bulk caller-fetch across every
        // selected placeholder, the per-row MERGE writes inside a single transaction, then ONE
        // bulk delete of every old caller→placeholder edge. Replaces an earlier N-queries-per-
        // -placeholder loop (3 round-trips × ~127 placeholders ≈ 400 saved JNI executes).
        int rewired = rewireAllBatched(kuzu, rewireMap);
        dropOrphanPlaceholders(kuzu, projectId);
        return rewired;
    }

    private record Placeholder(String id, String name, long arity) {}

    private static int rewireAllBatched(EmbeddedKuzu kuzu, List<String[]> rewireMap) {
        if (rewireMap.isEmpty()) return 0;

        // placeholderId → candidateId lookup so we can group fetched callers by which placeholder
        // they came in via and resolve to the right rewire target.
        Map<String, String> placeholderToCandidate = new HashMap<>(rewireMap.size() * 2);
        StringBuilder idList = new StringBuilder();
        for (int i = 0; i < rewireMap.size(); i++) {
            String[] pair = rewireMap.get(i);
            placeholderToCandidate.put(pair[0], pair[1]);
            if (i > 0) idList.append(", ");
            idList.append('\'').append(pair[0].replace("'", "\\'")).append('\'');
        }

        // One bulk query: every CALLS edge that points at any selected placeholder, with its
        // column-by-column properties (Kuzu has no properties() map projection).
        List<Map<String, Object>> callers = kuzu.read(
                "MATCH (caller)-[r:CALLS]->(p:Node) "
                        + "WHERE p.id IN [" + idList + "] "
                        + "RETURN caller.id AS callerId, p.id AS placeholderId, "
                        + "       r.confidence AS confidence, "
                        + "       r.callSiteLine AS callSiteLine, "
                        + "       r.kind AS kind, "
                        + "       r.via AS via, "
                        + "       r.viaMethodReference AS viaMethodReference, "
                        + "       r.ambiguous AS ambiguous");
        if (callers.isEmpty()) return 0;

        // Track which placeholders actually had >=1 incoming CALLS (the "rewired" count for the
        // user-facing report).
        java.util.Set<String> placeholdersWithCallers = new java.util.HashSet<>();
        for (Map<String, Object> c : callers) placeholdersWithCallers.add((String) c.get("placeholderId"));

        kuzu.write("BEGIN TRANSACTION", Map.of());
        try {
            // Write new caller→candidate edges. Still per-row MERGE (Kuzu can't UNWIND map params)
            // but inside one transaction so commit overhead is amortised.
            for (Map<String, Object> c : callers) {
                String callerId = (String) c.get("callerId");
                String candidateId = placeholderToCandidate.get(c.get("placeholderId"));
                if (candidateId == null) continue;
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

            // ONE bulk delete for every old caller→placeholder edge across every placeholder.
            kuzu.write(
                    "MATCH (caller)-[r:CALLS]->(p:Node) "
                            + "WHERE p.id IN [" + idList + "] "
                            + "DELETE r",
                    Map.of());

            kuzu.write("COMMIT", Map.of());
        } catch (RuntimeException e) {
            try { kuzu.write("ROLLBACK", Map.of()); } catch (RuntimeException ignored) { /* roll-forward */ }
            throw e;
        }
        return placeholdersWithCallers.size();
    }

    private static long parseArity(String fqName) {
        if (fqName == null) return -1;
        int colon = fqName.lastIndexOf(':');
        if (colon < 0 || colon == fqName.length() - 1) return -1;
        try { return Long.parseLong(fqName.substring(colon + 1)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static double numericConfidence(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.5;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0.5; }
    }

    private static void dropOrphanPlaceholders(EmbeddedKuzu kuzu, String projectId) {
        // Materialise orphan ids first (DETACH DELETE inside an OPTIONAL MATCH chain would mutate
        // the rows we're iterating), then issue ONE bulk delete with an inlined id list. Used to
        // be a per-row delete loop — fine for tiny orphan sets but a ~5 ms × N JNI cost on bigger
        // codebases.
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
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < orphans.size(); i++) {
            if (i > 0) ids.append(", ");
            String id = (String) orphans.get(i).get("id");
            if (id == null) continue;
            ids.append('\'').append(id.replace("'", "\\'")).append('\'');
        }
        kuzu.write("MATCH (n:Node) WHERE n.id IN [" + ids + "] DETACH DELETE n", Map.of());
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
     * Delete scan-managed nodes whose id isn't in {@code touchedIds}. {@code touchedIds} is the
     * snapshot of every node id the ingestor saw during the scan (written or skipped), as
     * returned by {@code KuzuIngestor.touchedNodeIds()}. Replaces the prior lastIngestedAt-based
     * stale detection, which required bulk-bumping a timestamp on every skipped row at flush —
     * a 3-4 s cost on Windows. The trade-off: {@code lastIngestedAt} now reflects "when this row
     * was last actually written" rather than "when this scan last touched the project", which is
     * the more useful semantic for {@code cv_changes} anyway.
     */
    public static int cleanupStale(EmbeddedKuzu kuzu, String projectId, java.util.Set<String> touchedIds) {
        StringBuilder labelList = new StringBuilder();
        for (int i = 0; i < STALE_CANDIDATE_LABELS.size(); i++) {
            if (i > 0) labelList.append(", ");
            labelList.append('\'').append(STALE_CANDIDATE_LABELS.get(i)).append('\'');
        }

        // Materialise the candidate set first, then set-difference in Java. Kuzu's NOT n.id IN [N items]
        // with thousands of inlined ids is brittle (Cypher length, parser perf); per-chunk delete
        // by inverted IN-list is more predictable.
        List<Map<String, Object>> candidates = kuzu.read(
                "MATCH (n:Node) WHERE n.projectId = $pid AND n.label IN [" + labelList + "] "
                        + "RETURN n.id AS id",
                Map.of("pid", projectId));
        if (candidates.isEmpty()) return 0;
        List<String> stale = new java.util.ArrayList<>();
        for (Map<String, Object> r : candidates) {
            String id = (String) r.get("id");
            if (id != null && !touchedIds.contains(id)) stale.add(id);
        }
        if (stale.isEmpty()) return 0;

        // Delete in chunks of 2000 so the inlined id list stays manageable.
        final int chunkSize = 2000;
        for (int from = 0; from < stale.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, stale.size());
            StringBuilder ids = new StringBuilder((to - from) * 18);
            for (int i = from; i < to; i++) {
                if (i > from) ids.append(", ");
                ids.append('\'').append(stale.get(i).replace("'", "\\'")).append('\'');
            }
            kuzu.write("MATCH (n:Node) WHERE n.id IN [" + ids + "] DETACH DELETE n", Map.of());
        }
        return stale.size();
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

}
