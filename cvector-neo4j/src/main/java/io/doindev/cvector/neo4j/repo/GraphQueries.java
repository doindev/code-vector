package io.doindev.cvector.neo4j.repo;

import io.doindev.cvector.neo4j.Neo4jClient;
import org.neo4j.driver.Record;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GraphQueries {

    private final Neo4jClient client;

    public GraphQueries(Neo4jClient client) {
        this.client = client;
    }

    public Map<String, Long> nodeCounts(String projectId) {
        // Empty-workspace tolerance: callers may pass null when no project is registered
        // (the ActiveProject bean now uses a (null, null, null) placeholder). Cypher
        // parameters can't carry null without driver rejection; short-circuit here so
        // every consumer (REST controllers, MCP tools) gets a clean empty result.
        if (projectId == null) return Map.of();
        var rows = client.read(
                "MATCH (n) WHERE n.projectId = $pid RETURN labels(n)[0] AS label, count(*) AS c",
                Map.of("pid", projectId)
        );
        Map<String, Long> out = new LinkedHashMap<>();
        for (Record r : rows) {
            out.put(r.get("label").asString(), r.get("c").asLong());
        }
        return out;
    }

    public Map<String, Long> edgeCounts(String projectId) {
        if (projectId == null) return Map.of();
        var rows = client.read(
                "MATCH (a)-[r]->(b) WHERE a.projectId = $pid AND b.projectId = $pid "
                        + "RETURN type(r) AS type, count(*) AS c",
                Map.of("pid", projectId)
        );
        Map<String, Long> out = new LinkedHashMap<>();
        for (Record r : rows) {
            out.put(r.get("type").asString(), r.get("c").asLong());
        }
        return out;
    }

    public List<Map<String, Object>> findSymbol(String projectId, String symbol) {
        // The fourth `STARTS WITH $sym + '('` predicate lets cv_explain resolve a partially-
        // qualified Method like "io.foo.Bar.baz" when the graph stores it as
        // "io.foo.Bar.baz(int, String)". Without it the only working shapes were the bare
        // name and the full fqName-with-signature; the intermediate form an agent naturally
        // types (it knows the class but not the parameter list) silently returned no hits.
        var rows = client.read(
                "MATCH (n) WHERE n.projectId = $pid AND "
                        + "(n.fqName = $sym OR n.fqName ENDS WITH '.' + $sym OR n.name = $sym "
                        + " OR n.fqName STARTS WITH $sym + '(') "
                        + "RETURN labels(n)[0] AS label, n.fqName AS fqName, n.name AS name, "
                        + "n.id AS id, n.startLine AS startLine, n.fileId AS fileId LIMIT 25",
                Map.of("pid", projectId, "sym", symbol)
        );
        return toMaps(rows);
    }

    public List<Map<String, Object>> callers(String projectId, String id) {
        return toMaps(client.read(
                "MATCH (caller:Method)-[r:CALLS]->(callee {id: $id, projectId: $pid}) "
                        + "RETURN caller.fqName AS fqName, caller.name AS name, "
                        + "r.callSiteLine AS callSiteLine, caller.id AS id LIMIT 100",
                Map.of("id", id, "pid", projectId)
        ));
    }

    public List<Map<String, Object>> callees(String projectId, String id) {
        return toMaps(client.read(
                "MATCH (caller {id: $id, projectId: $pid})-[r:CALLS]->(callee:Method) "
                        + "RETURN callee.fqName AS fqName, callee.name AS name, "
                        + "r.callSiteLine AS callSiteLine, callee.id AS id LIMIT 100",
                Map.of("id", id, "pid", projectId)
        ));
    }

    public List<Map<String, Object>> impactDownstream(String projectId, String id, int depth) {
        String cypher = "MATCH (start {id: $id, projectId: $pid}) "
                + "CALL apoc.path.subgraphNodes(start, {relationshipFilter: 'CALLS>|REFERENCES>', maxLevel: $depth}) YIELD node "
                + "RETURN labels(node)[0] AS label, node.fqName AS fqName, node.name AS name, node.id AS id";
        try {
            return toMaps(client.read(cypher, Map.of("id", id, "pid", projectId, "depth", depth)));
        } catch (RuntimeException apocMissing) {
            return toMaps(client.read(
                    "MATCH (start {id: $id, projectId: $pid}) "
                            + "MATCH (start)-[:CALLS|REFERENCES*1.." + Math.max(1, depth) + "]->(impacted) "
                            + "WHERE impacted.projectId = $pid "
                            + "RETURN DISTINCT labels(impacted)[0] AS label, impacted.fqName AS fqName, "
                            + "impacted.name AS name, impacted.id AS id LIMIT 500",
                    Map.of("id", id, "pid", projectId)
            ));
        }
    }

    public Map<String, Object> nodeById(String projectId, String id) {
        var rows = client.read(
                "MATCH (n {id: $id, projectId: $pid}) RETURN n, labels(n) AS labels LIMIT 1",
                Map.of("id", id, "pid", projectId)
        );
        if (rows.isEmpty()) return Map.of();
        Record r = rows.get(0);
        Map<String, Object> out = new HashMap<>(r.get("n").asMap());
        out.put("label", r.get("labels").asList().get(0));
        return out;
    }

    public Map<String, Object> fileOf(String projectId, String fileId) {
        var rows = client.read(
                "MATCH (f:File {id: $id, projectId: $pid}) RETURN f.path AS path, f.language AS language",
                Map.of("id", fileId, "pid", projectId)
        );
        return rows.isEmpty() ? Map.of() : new HashMap<>(rows.get(0).asMap());
    }

    public List<Map<String, Object>> raw(String cypher, Map<String, Object> params) {
        return toMaps(client.read(cypher, params));
    }

    public List<Map<String, Object>> rawAuto(String cypher, Map<String, Object> params) {
        return rawAutoTyped(cypher, params).rows();
    }

    public QueryResult rawAutoTyped(String cypher, Map<String, Object> params) {
        if (looksLikeWrite(cypher)) {
            client.write(cypher, params);
            return new QueryResult(true, List.of());
        }
        return new QueryResult(false, toMaps(client.read(cypher, params)));
    }

    public record QueryResult(boolean isWrite, List<Map<String, Object>> rows) {}

    private static boolean looksLikeWrite(String cypher) {
        if (cypher == null) return false;
        String upper = cypher.toUpperCase();
        return upper.contains("CREATE ") || upper.contains("MERGE ") || upper.contains(" SET ")
                || upper.contains("DELETE ") || upper.contains("REMOVE ") || upper.contains("DROP ");
    }

    private static List<Map<String, Object>> toMaps(List<Record> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Record r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : r.keys()) {
                m.put(k, r.get(k).asObject());
            }
            out.add(m);
        }
        return out;
    }
}
