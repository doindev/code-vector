package io.doindev.cvector.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP resources backed by the project graph. Clients fetch these as JSON snapshots without
 * having to compose tool calls. Resources are project-scoped via the active {@code McpActiveProject}.
 */
public class CvectorResources {

    private static final String MIME_JSON = "application/json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GraphQueries q;
    private final McpServerConfig.McpActiveProject project;

    public CvectorResources(GraphQueries q, McpServerConfig.McpActiveProject project) {
        this.q = q;
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
        return out;
    }

    private McpServerFeatures.SyncResourceSpecification resource(
            String uri, String name, String description,
            java.util.function.Function<McpSchema.ReadResourceRequest, Object> reader) {
        McpSchema.Resource res = new McpSchema.Resource(uri, name, description, MIME_JSON, null);
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
        out.put("nodes", q.nodeCounts(project.projectId()));
        out.put("edges", q.edgeCounts(project.projectId()));
        return out;
    }

    private Map<String, Object> readSchema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("nodeLabels", q.nodeCounts(project.projectId()));
        out.put("relationshipTypes", q.edgeCounts(project.projectId()));
        return out;
    }

    private Map<String, Object> readFiles() {
        List<Map<String, Object>> rows = q.raw(
                "MATCH (f:File {projectId: $pid}) "
                        + "OPTIONAL MATCH (f)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methodCount "
                        + "RETURN f.path AS path, f.language AS language, f.lineCount AS lineCount, "
                        + "       methodCount, f.lastIngestedAt AS lastIngestedAt "
                        + "ORDER BY f.path",
                Map.of("pid", project.projectId()));
        return Map.of("projectId", project.projectId(), "count", rows.size(), "files", rows);
    }

    private Map<String, Object> readProjects() {
        List<Map<String, Object>> rows = q.raw(
                "MATCH (p:Project) "
                        + "OPTIONAL MATCH (p)<-[:IN_PROJECT]-(f:File) "
                        + "WITH p, count(f) AS fileCount "
                        + "RETURN p.projectId AS projectId, p.name AS name, p.rootPath AS rootPath, "
                        + "       p.lastScanCommit AS lastScanCommit, fileCount "
                        + "ORDER BY p.name",
                Map.of());
        return Map.of("active", project.projectId(), "projects", rows);
    }

    private Map<String, Object> readHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("godFiles", q.raw(
                "MATCH (f:File {projectId: $pid})-[:CONTAINS]->(:Class)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 "
                        + "RETURN f.path AS path, methods ORDER BY methods DESC LIMIT 20",
                Map.of("pid", project.projectId())));
        out.put("godClasses", q.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 "
                        + "RETURN c.fqName AS fqName, methods ORDER BY methods DESC LIMIT 20",
                Map.of("pid", project.projectId())));
        out.put("longMethods", q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE m.startLine IS NOT NULL AND m.endLine IS NOT NULL "
                        + "AND (m.endLine - m.startLine) >= 80 "
                        + "RETURN m.fqName AS fqName, (m.endLine - m.startLine) AS lines "
                        + "ORDER BY lines DESC LIMIT 20",
                Map.of("pid", project.projectId())));
        out.put("deadCode", q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE NOT EXISTS { MATCH ()-[:CALLS]->(m) } "
                        + "AND coalesce(m.isTest, false) = false "
                        + "AND m.name <> 'main' "
                        + "AND coalesce(m.isConstructor, false) = false "
                        + "AND coalesce(m.visibility, '') IN ['private', 'package'] "
                        + "RETURN m.fqName AS fqName ORDER BY m.fqName LIMIT 20",
                Map.of("pid", project.projectId())));
        return out;
    }

    private Map<String, Object> readOnboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("projectName", project.name());
        out.put("nodes", q.nodeCounts(project.projectId()));
        out.put("edges", q.edgeCounts(project.projectId()));
        out.put("languageMix", q.raw(
                "MATCH (f:File {projectId: $pid}) "
                        + "RETURN f.language AS language, count(*) AS files ORDER BY files DESC",
                Map.of("pid", project.projectId())));
        out.put("topCallHubs", q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "OPTIONAL MATCH (m)-[out:CALLS]->() "
                        + "OPTIONAL MATCH (m)<-[in:CALLS]-() "
                        + "WITH m, count(DISTINCT out) AS outDeg, count(DISTINCT in) AS inDeg "
                        + "WHERE NOT m.fqName STARTS WITH 'unresolved.' "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, (outDeg + inDeg) AS total "
                        + "ORDER BY total DESC LIMIT 10",
                Map.of("pid", project.projectId())));
        out.put("apiEndpoints", q.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "RETURN e.httpMethod AS httpMethod, e.path AS path, e.framework AS framework "
                        + "ORDER BY path LIMIT 50",
                Map.of("pid", project.projectId())));
        out.put("dependencies", q.raw(
                "MATCH (d:MavenDependency {projectId: $pid}) "
                        + "RETURN d.fqName AS fqName, d.scope AS scope ORDER BY d.fqName",
                Map.of("pid", project.projectId())));
        return out;
    }

    private Map<String, Object> readInfrastructure() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("apiEndpoints", q.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "RETURN e.httpMethod AS httpMethod, e.path AS path, e.framework AS framework "
                        + "ORDER BY path",
                Map.of("pid", project.projectId())));
        out.put("queueListeners", q.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS fqName",
                Map.of("pid", project.projectId())));
        out.put("scheduledJobs", q.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isScheduled, false) = true "
                        + "RETURN m.fqName AS fqName",
                Map.of("pid", project.projectId())));
        out.put("configKeys", q.raw(
                "MATCH (c:ConfigKey {projectId: $pid}) RETURN c.fqName AS fqName ORDER BY c.fqName",
                Map.of("pid", project.projectId())));
        out.put("envVars", q.raw(
                "MATCH (e:EnvVar {projectId: $pid}) RETURN e.fqName AS fqName, e.value AS value ORDER BY e.fqName",
                Map.of("pid", project.projectId())));
        out.put("containerImages", q.raw(
                "MATCH (i:ContainerImage {projectId: $pid}) RETURN i.fqName AS fqName, i.repository AS repository, i.tag AS tag",
                Map.of("pid", project.projectId())));
        out.put("containerPorts", q.raw(
                "MATCH (p:ContainerPort {projectId: $pid}) RETURN p.port AS port, p.protocol AS protocol",
                Map.of("pid", project.projectId())));
        out.put("terraformResources", q.raw(
                "MATCH (r:Resource {projectId: $pid}) RETURN r.fqName AS fqName, r.resourceType AS resourceType, r.provider AS provider",
                Map.of("pid", project.projectId())));
        return out;
    }

    private Map<String, Object> readGuard() {
        long godFiles = countRule(
                "MATCH (f:File {projectId: $pid})-[:CONTAINS]->(:Class)-[:CONTAINS]->(m:Method) "
                        + "WITH f, count(m) AS methods WHERE methods >= 30 RETURN count(f) AS c");
        long godClasses = countRule(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "WITH c, count(m) AS methods WHERE methods >= 20 RETURN count(c) AS c");
        long longMethods = countRule(
                "MATCH (m:Method {projectId: $pid}) "
                        + "WHERE (m.endLine - m.startLine) >= 80 RETURN count(m) AS c");
        boolean pass = godFiles == 0 && godClasses == 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("pass", pass);
        out.put("errors", godFiles + godClasses);
        out.put("warnings", longMethods);
        out.put("breakdown", Map.of(
                "godFiles", godFiles,
                "godClasses", godClasses,
                "longMethods", longMethods));
        return out;
    }

    private long countRule(String cypher) {
        var rows = q.raw(cypher, Map.of("pid", project.projectId()));
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("c")).longValue();
    }
}
