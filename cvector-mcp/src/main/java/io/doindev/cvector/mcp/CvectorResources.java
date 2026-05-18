package io.doindev.cvector.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP resources backed by the project graph. Clients fetch these as JSON snapshots without
 * having to compose tool calls. Resources are project-scoped via the active {@code McpActiveProject}.
 *
 * <p>Every resource here is backend-agnostic — they route through {@link GraphStore} which has
 * Neo4j and Kuzu implementations.
 */
public class CvectorResources {

    private static final String MIME_JSON = "application/json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GraphStore store;
    private final McpServerConfig.McpActiveProject project;

    public CvectorResources(GraphStore store, McpServerConfig.McpActiveProject project) {
        this.store = store;
        this.project = project;
    }

    public List<McpServerFeatures.SyncResourceSpecification> getResources() {
        List<McpServerFeatures.SyncResourceSpecification> out = new ArrayList<>();
        out.add(resource("cvector://stats", "stats",
                "Node and edge counts by type for the active project.",
                req -> readStats()));
        out.add(resource("cvector://schema", "schema",
                "All node labels and relationship types in the active project's graph with counts.",
                req -> readSchema()));
        out.add(resource("cvector://files", "files",
                "All File nodes in the active project with language and ingestion metadata.",
                req -> readFiles()));
        out.add(resource("cvector://projects", "projects",
                "All projects tracked by this cvector installation.",
                req -> readProjects()));
        out.add(resource("cvector://health", "health",
                "Summary of god-files, god-classes, long methods, and dead code (top 20 each).",
                req -> readHealth()));
        out.add(resource("cvector://onboard", "onboard",
                "Full architecture briefing: language mix, top hubs, controllers, endpoints, dependencies.",
                req -> readOnboard()));
        out.add(resource("cvector://infrastructure", "infrastructure",
                "Queues, scheduled jobs, message listeners, exposed endpoints, config keys, env vars.",
                req -> readInfrastructure()));
        out.add(resource("cvector://guard", "guard",
                "Quality gate snapshot: severity totals and whether the build would block.",
                req -> readGuard()));
        out.add(resource("cvector://communities", "communities",
                "Functional clusters in the call graph (Louvain) with size and a top-fqName sample per cluster.",
                req -> readCommunities()));
        return out;
    }

    private McpServerFeatures.SyncResourceSpecification resource(
            String uri, String name, String description,
            java.util.function.Function<McpSchema.ReadResourceRequest, Object> reader) {
        McpSchema.Resource res = new McpSchema.Resource(uri, name, null, description, MIME_JSON, null, null, null);
        return new McpServerFeatures.SyncResourceSpecification(res, (exchange, req) -> {
            try {
                Object payload = reader.apply(req);
                String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(uri, MIME_JSON, text)));
            } catch (Exception e) {
                Map<String, Object> err = Map.of("error", e.getClass().getSimpleName(), "message",
                        e.getMessage() == null ? "" : e.getMessage());
                try {
                    String text = JSON.writeValueAsString(err);
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(uri, MIME_JSON, text)));
                } catch (Exception inner) {
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(uri, "text/plain",
                                    "{\"error\":\"serialization-failed\"}")));
                }
            }
        });
    }

    private Map<String, Object> readStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("projectName", project.name());
        out.put("nodes", store.nodeCounts(project.projectId()));
        out.put("edges", store.edgeCounts(project.projectId()));
        return out;
    }

    private Map<String, Object> readSchema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("nodeLabels", store.nodeCounts(project.projectId()));
        out.put("relationshipTypes", store.edgeCounts(project.projectId()));
        return out;
    }

    private Map<String, Object> readFiles() {
        List<Map<String, Object>> rows = store.fileInventory(project.projectId());
        return Map.of("projectId", project.projectId(), "count", rows.size(), "files", rows);
    }

    private Map<String, Object> readProjects() {
        return Map.of("active", project.projectId(),
                "projects", store.projectsList());
    }

    private Map<String, Object> readHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.putAll(store.healthRollup(project.projectId()));
        return out;
    }

    private Map<String, Object> readOnboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("projectName", project.name());
        out.put("nodes", store.nodeCounts(project.projectId()));
        out.put("edges", store.edgeCounts(project.projectId()));
        out.put("dependencies", store.mavenDependencies(project.projectId()));
        out.putAll(store.onboardSummary(project.projectId()));
        return out;
    }

    private Map<String, Object> readInfrastructure() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.putAll(store.infrastructureSummary(project.projectId()));
        return out;
    }

    private Map<String, Object> readGuard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.putAll(store.guardSummary(project.projectId()));
        return out;
    }

    /**
     * Compute Louvain communities over the method call graph and return per-cluster summaries:
     * size, modularity contribution if known, plus the top-5 fqNames as a peek. We cap the
     * returned cluster count to keep the JSON payload bounded; clients that want everything
     * should call {@code cv_communities} with a higher limit.
     */
    private Map<String, Object> readCommunities() {
        String pid = project.projectId();
        GraphStore.MethodCallGraph g = store.methodCallGraph(pid);
        String[] fqNames = g.fqNames();
        List<int[]> edges = g.edges();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", pid);
        out.put("methodCount", fqNames.length);
        out.put("edgeCount", edges.size());
        if (fqNames.length == 0) {
            out.put("communities", List.of());
            return out;
        }
        int[] assignments = LouvainCommunityDetector.detect(fqNames.length, edges).community();
        Map<Integer, List<String>> byCluster = new LinkedHashMap<>();
        for (int i = 0; i < assignments.length; i++) {
            byCluster.computeIfAbsent(assignments[i], k -> new ArrayList<>()).add(fqNames[i]);
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        // Sort clusters by descending size; cap to 50 to avoid mega payloads.
        byCluster.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, List<String>>>comparingInt(e -> e.getValue().size()).reversed())
                .limit(50)
                .forEach(e -> {
                    List<String> members = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("clusterId", e.getKey());
                    row.put("size", members.size());
                    row.put("members", members.subList(0, Math.min(5, members.size())));
                    clusters.add(row);
                });
        out.put("communities", clusters);
        return out;
    }
}
