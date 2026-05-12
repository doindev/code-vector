package io.doindev.cvector.neo4j;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.store.GraphIngestor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Ingestor implements GraphIngestor {

    private static final int BATCH_SIZE = 500;

    private final Neo4jClient client;
    private final Map<String, List<Map<String, Object>>> nodeBatches = new LinkedHashMap<>();
    private final Map<EdgeBucket, List<Map<String, Object>>> edgeBatches = new LinkedHashMap<>();
    private int totalNodes;
    private int totalEdges;

    public Ingestor(Neo4jClient client) {
        this.client = client;
    }

    @Override
    public synchronized void accept(GraphEvent event) {
        if (event instanceof GraphEvent.NodeUpsert n) {
            bufferNode(n.key(), n.props());
        } else if (event instanceof GraphEvent.EdgeUpsert e) {
            bufferEdge(e.from(), e.type(), e.to(), e.props());
        } else if (event instanceof GraphEvent.NodeRemove r) {
            removeNode(r.key());
        }
    }

    private void bufferNode(NodeKey key, Map<String, Object> props) {
        Map<String, Object> row = new HashMap<>(props);
        row.put("id", key.id());
        row.put("projectId", key.projectId());
        row.put("fqName", key.fqName());
        List<Map<String, Object>> batch = nodeBatches.computeIfAbsent(key.label(), k -> new ArrayList<>());
        batch.add(row);
        if (batch.size() >= BATCH_SIZE) {
            flushNodes(key.label());
        }
    }

    private void bufferEdge(NodeKey from, String type, NodeKey to, Map<String, Object> props) {
        EdgeBucket bucket = new EdgeBucket(from.label(), type, to.label());
        Map<String, Object> row = new HashMap<>();
        row.put("fromId", from.id());
        row.put("toId", to.id());
        row.put("props", props == null ? Map.of() : props);
        edgeBatches.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
    }

    private void removeNode(NodeKey key) {
        client.write(
                "MATCH (n:" + key.label() + " {id: $id}) DETACH DELETE n",
                Map.of("id", key.id())
        );
    }

    private void flushNodes(String label) {
        List<Map<String, Object>> rows = nodeBatches.get(label);
        if (rows == null || rows.isEmpty()) return;
        String cypher = "UNWIND $rows AS row "
                + "MERGE (n:" + label + " {id: row.id}) "
                + "SET n += row, n.lastIngestedAt = datetime()";
        client.write(cypher, Map.of("rows", new ArrayList<>(rows)));
        totalNodes += rows.size();
        rows.clear();
    }

    private void flushEdges(EdgeBucket bucket) {
        List<Map<String, Object>> rows = edgeBatches.get(bucket);
        if (rows == null || rows.isEmpty()) return;
        int from = 0;
        while (from < rows.size()) {
            int to = Math.min(from + BATCH_SIZE, rows.size());
            List<Map<String, Object>> chunk = new ArrayList<>(rows.subList(from, to));
            String cypher = "UNWIND $rows AS row "
                    + "MATCH (a:" + bucket.fromLabel() + " {id: row.fromId}), (b:" + bucket.toLabel() + " {id: row.toId}) "
                    + "MERGE (a)-[r:" + bucket.type() + "]->(b) "
                    + "SET r += row.props";
            client.write(cypher, Map.of("rows", chunk));
            totalEdges += chunk.size();
            from = to;
        }
        rows.clear();
    }

    public synchronized void flush() {
        new ArrayList<>(nodeBatches.keySet()).forEach(this::flushNodes);
        new ArrayList<>(edgeBatches.keySet()).forEach(this::flushEdges);
    }

    public int totalNodes() { return totalNodes; }
    public int totalEdges() { return totalEdges; }

    @Override
    public void close() {
        flush();
    }

    private record EdgeBucket(String fromLabel, String type, String toLabel) {}
}
