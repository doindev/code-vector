package io.doindev.cvector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.doindev.cvector.core.util.UnionFind;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigLoader;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Violation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CvectorTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OSV_URL = "https://api.osv.dev/v1/query";

    private final GraphQueries q;
    private final Neo4jClient client;
    private final McpServerConfig.McpActiveProject project;

    public CvectorTools(GraphQueries graphQueries, McpServerConfig.McpActiveProject activeProject) {
        this(graphQueries, null, activeProject);
    }

    public CvectorTools(GraphQueries graphQueries, Neo4jClient client, McpServerConfig.McpActiveProject activeProject) {
        this.q = graphQueries;
        this.client = client;
        this.project = activeProject;
    }

    @Tool(name = "cv_stats", description = "Graph statistics for the active cvector project: node counts by label and edge counts by type.")
    public Map<String, Object> stats() {
        return Map.of(
                "project", project.name(),
                "projectId", project.projectId(),
                "nodes", q.nodeCounts(project.projectId()),
                "edges", q.edgeCounts(project.projectId())
        );
    }

    @Tool(name = "cv_health", description = "Health check: Neo4j connectivity, graph size, last scan commit, and core counts.")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        boolean ok = client != null && client.ping();
        out.put("neo4j", Map.of("ok", ok, "uri", client == null ? null : client.uri()));
        Map<String, Long> nodes = q.nodeCounts(project.projectId());
        Map<String, Long> edges = q.edgeCounts(project.projectId());
        out.put("nodes", nodes);
        out.put("edges", edges);
        var lastCommit = q.raw(
                "MATCH (p:Project {projectId: $pid}) RETURN p.lastScanCommit AS sha, p.rootPath AS root",
                Map.of("pid", project.projectId())
        );
        out.put("lastScan", lastCommit.isEmpty() ? Map.of() : lastCommit.get(0));
        return out;
    }

    @Tool(name = "cv_projects", description = "Show the active cvector project context.")
    public Map<String, Object> projects() {
        return Map.of(
                "active", Map.of(
                        "projectId", project.projectId(),
                        "name", project.name(),
                        "rootPath", project.rootPath()
                )
        );
    }

    @Tool(name = "cv_search",
            description = "Search graph nodes (Class, Method, Field, Table, Column, ApiEndpoint, ConfigKey, EnvVar, MavenDependency) by substring of name or fqName.")
    public Map<String, Object> search(
            @ToolParam(description = "Search substring; supports * wildcards.") String query,
            @ToolParam(description = "Max results (default 25).", required = false) Integer limit,
            @ToolParam(description = "Restrict to a single node label (e.g. 'Method').", required = false) String label) {
        int lim = limit == null ? 25 : limit;
        String regex = "(?i).*" + query.replace("*", ".*") + ".*";
        String labelFilter = label != null && !label.isBlank() ? "AND any(l IN labels(n) WHERE l = $label) " : "";
        String cypher = "MATCH (n) WHERE n.projectId = $pid "
                + "AND (n.fqName =~ $regex OR n.name =~ $regex) " + labelFilter
                + "RETURN labels(n)[0] AS label, n.fqName AS fqName, n.name AS name, n.id AS id LIMIT $lim";
        List<Map<String, Object>> rows = q.raw(cypher, Map.of(
                "pid", project.projectId(),
                "regex", regex,
                "label", label == null ? "" : label,
                "lim", lim
        ));
        return Map.of("query", query, "count", rows.size(), "results", rows);
    }

    @Tool(name = "cv_explain",
            description = "Full context for a symbol: type, file:line, callers (incoming CALLS), callees (outgoing CALLS).")
    public Map<String, Object> explain(@ToolParam(description = "Symbol name (fully-qualified or last segment).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = q.findSymbol(project.projectId(), symbol);
        out.put("query", symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        if ("Method".equals(hit.get("label"))) {
            out.put("callers", q.callers(project.projectId(), id));
            out.put("callees", q.callees(project.projectId(), id));
        }
        if (hit.get("fileId") != null) {
            out.put("file", q.fileOf(project.projectId(), (String) hit.get("fileId")));
        }
        return out;
    }

    @Tool(name = "cv_impact",
            description = "Downstream impact of changing a symbol: BFS via CALLS/REFERENCES edges, returns all reachable symbols.")
    public Map<String, Object> impact(
            @ToolParam(description = "Symbol name (fully-qualified or last segment).") String symbol,
            @ToolParam(description = "Max traversal depth (default 3).", required = false) Integer depth) {
        int d = depth == null ? 3 : depth;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = q.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("depth", d);
        out.put("impacted", q.impactDownstream(project.projectId(), (String) hit.get("id"), d));
        return out;
    }

    @Tool(name = "cv_test_impact",
            description = "Find test methods (@Test-annotated) that transitively reach a given symbol.")
    public Map<String, Object> testImpact(
            @ToolParam(description = "Symbol name.") String symbol,
            @ToolParam(description = "Max traversal depth (default 5).", required = false) Integer depth) {
        int d = depth == null ? 5 : depth;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = q.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        String cypher = "MATCH (t:Method {projectId: $pid, isTest: true}) "
                + "MATCH (sym {id: $id, projectId: $pid}) "
                + "MATCH p = shortestPath((t)-[:CALLS|REFERENCES*1.." + Math.max(1, d) + "]->(sym)) "
                + "RETURN DISTINCT t.fqName AS test, t.fileId AS fileId, length(p) AS depth ORDER BY depth ASC LIMIT 200";
        out.put("found", true);
        out.put("symbol", hit);
        out.put("tests", q.raw(cypher, Map.of("pid", project.projectId(), "id", id)));
        return out;
    }

    @Tool(name = "cv_context",
            description = "Everything a class or method contains and references: methods, called methods, fields, importing files.")
    public Map<String, Object> context(@ToolParam(description = "Symbol name (Class or Method).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = q.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("contains", q.raw(
                "MATCH (n {id: $id, projectId: $pid})-[:CONTAINS]->(child) "
                        + "RETURN labels(child)[0] AS label, child.fqName AS fqName LIMIT 200",
                Map.of("id", id, "pid", project.projectId())
        ));
        out.put("callers", q.callers(project.projectId(), id));
        out.put("callees", q.callees(project.projectId(), id));
        return out;
    }

    @Tool(name = "cv_rename",
            description = "Graph-aware rename impact: all callers, references, importing files, and the target's definition for a symbol about to be renamed.")
    public Map<String, Object> rename(@ToolParam(description = "Symbol to rename (fully-qualified or last segment).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = q.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("callers", q.callers(project.projectId(), id));
        out.put("references", q.raw(
                "MATCH (src)-[:REFERENCES]->(target {id: $id, projectId: $pid}) "
                        + "RETURN labels(src)[0] AS label, src.fqName AS fqName, src.fileId AS fileId, src.startLine AS line LIMIT 500",
                Map.of("id", id, "pid", project.projectId())
        ));
        out.put("importingFiles", q.raw(
                "MATCH (f:File)-[:IMPORTS]->(target {id: $id, projectId: $pid}) "
                        + "RETURN f.path AS path LIMIT 200",
                Map.of("id", id, "pid", project.projectId())
        ));
        return out;
    }

    @Tool(name = "cv_changes",
            description = "Recently-ingested graph nodes within a time window (e.g. '24h', '7d').")
    public Map<String, Object> changes(
            @ToolParam(description = "Time window: 24h, 7d, 30m, etc. (default 24h).", required = false) String since,
            @ToolParam(description = "Max rows (default 50).", required = false) Integer limit) {
        String window = since == null || since.isBlank() ? "24h" : since;
        int lim = limit == null ? 50 : limit;
        Duration d = parseDuration(window);
        String cutoff = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minus(d).toString();
        var rows = q.raw(
                "MATCH (n) WHERE n.projectId = $pid AND n.lastIngestedAt > datetime($cutoff) "
                        + "RETURN labels(n)[0] AS label, n.fqName AS fqName, toString(n.lastIngestedAt) AS lastIngestedAt "
                        + "ORDER BY n.lastIngestedAt DESC LIMIT $lim",
                Map.of("pid", project.projectId(), "cutoff", cutoff, "lim", lim)
        );
        return Map.of("since", window, "count", rows.size(), "changes", rows);
    }

    @Tool(name = "cv_onboard", description = "Full codebase briefing: project info, languages, top classes, REST endpoints, tables, config keys, dependencies, call-graph hubs.")
    public Map<String, Object> onboard() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("name", project.name(), "projectId", pid, "rootPath", project.rootPath()));
        out.put("nodes", q.nodeCounts(pid));
        out.put("edges", q.edgeCounts(pid));
        out.put("languages", q.raw(
                "MATCH (f:File {projectId: $pid}) RETURN f.language AS language, count(*) AS files ORDER BY files DESC",
                Map.of("pid", pid)));
        out.put("topClasses", q.raw(
                "MATCH (c:Class {projectId: $pid})-[:CONTAINS]->(m:Method) "
                        + "RETURN c.fqName AS fqName, count(m) AS methods ORDER BY methods DESC LIMIT 10",
                Map.of("pid", pid)));
        out.put("restEndpoints", q.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid}) RETURN e.httpMethod AS method, e.path AS path ORDER BY path",
                Map.of("pid", pid)));
        out.put("tables", q.raw(
                "MATCH (t:Table {projectId: $pid}) OPTIONAL MATCH (t)-[:CONTAINS]->(c:Column) "
                        + "RETURN t.name AS table, count(c) AS columns ORDER BY t.name",
                Map.of("pid", pid)));
        out.put("configKeys", q.raw(
                "MATCH (k:ConfigKey {projectId: $pid}) RETURN k.fqName AS key, k.value AS value ORDER BY k.fqName LIMIT 50",
                Map.of("pid", pid)));
        out.put("envVars", q.raw(
                "MATCH (e:EnvVar {projectId: $pid}) RETURN e.name AS name, e.value AS value ORDER BY e.name",
                Map.of("pid", pid)));
        out.put("dependencies", q.raw(
                "MATCH (d:MavenDependency {projectId: $pid}) "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, d.version AS version, d.scope AS scope "
                        + "ORDER BY groupId, artifactId",
                Map.of("pid", pid)));
        out.put("callGraphHubs", q.raw(
                "MATCH (m:Method {projectId: $pid}) "
                        + "OPTIONAL MATCH (m)-[out:CALLS]->() "
                        + "OPTIONAL MATCH ()-[in:CALLS]->(m) "
                        + "WITH m, count(DISTINCT out) AS outDeg, count(DISTINCT in) AS inDeg "
                        + "WHERE outDeg + inDeg > 0 "
                        + "RETURN m.fqName AS fqName, outDeg, inDeg, outDeg + inDeg AS total "
                        + "ORDER BY total DESC LIMIT 10",
                Map.of("pid", pid)));
        return out;
    }

    @Tool(name = "cv_rules", description = "Run the cvector rules engine and return violations by rule (uses .cvector/rules.yml if present).")
    public Map<String, Object> rules() {
        Path rulesYml = Paths.get(project.rootPath(), ".cvector", "rules.yml");
        RulesConfig cfg = RulesConfigLoader.loadOrDefault(rulesYml);
        RulesEngine.Report report = new RulesEngine(project.projectId(), q, cfg).run();

        List<Map<String, Object>> runs = new ArrayList<>();
        for (RulesEngine.RuleRun r : report.runs()) {
            List<Map<String, Object>> findings = new ArrayList<>();
            for (Violation v : r.findings()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", v.subject());
                m.put("message", v.message());
                m.put("line", v.line());
                m.put("severity", v.severity().name());
                findings.add(m);
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("rule", r.rule());
            run.put("severity", r.severity().name());
            run.put("violations", r.violations());
            run.put("findings", findings);
            runs.add(run);
        }
        return Map.of(
                "projectId", project.projectId(),
                "hasErrors", report.hasErrors(),
                "totals", report.bySeverity(),
                "totalViolations", report.totalViolations(),
                "runs", runs
        );
    }

    @Tool(name = "cv_communities",
            description = "Detect functional clusters in the call graph using leiden (default), louvain, or connected-components (union-find).")
    public Map<String, Object> communities(
            @ToolParam(description = "Algorithm: leiden (default), louvain, or connected-components.", required = false) String algorithm,
            @ToolParam(description = "Minimum community size (default 3).", required = false) Integer minSize,
            @ToolParam(description = "Max communities to return (default 10).", required = false) Integer limit) {
        String algo = (algorithm == null || algorithm.isBlank()) ? "leiden" : algorithm.toLowerCase();
        int min = minSize == null ? 3 : minSize;
        int lim = limit == null ? 10 : limit;
        String pid = project.projectId();

        MethodGraph g = loadMethodGraph(pid);
        if (g.fqNames.length == 0) return Map.of("communities", List.of(), "totalMethods", 0);

        DetectionResult det = detectCommunities(algo, g);
        if (det == null) {
            return Map.of(
                    "error", "unknown algorithm: " + algo,
                    "validAlgorithms", List.of("leiden", "louvain", "connected-components"));
        }

        Map<Integer, List<Integer>> groups = groupByCommunity(det.community);
        List<Map<String, Object>> result = topCommunities(groups, g.fqNames, min, lim);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", algo);
        out.put("totalMethods", g.fqNames.length);
        out.put("totalEdges", g.edgeCount);
        out.put("communitiesFound", groups.size());
        if (!Double.isNaN(det.modularity)) out.put("modularity", det.modularity);
        out.put("communities", result);
        return out;
    }

    private record MethodGraph(String[] fqNames, List<int[]> edges, int edgeCount) {}
    private record DetectionResult(int[] community, double modularity) {}

    private MethodGraph loadMethodGraph(String pid) {
        List<Map<String, Object>> methods = q.raw(
                "MATCH (m:Method {projectId: $pid}) RETURN m.id AS id, m.fqName AS fqName",
                Map.of("pid", pid));
        Map<String, Integer> indexOf = new HashMap<>(methods.size() * 2);
        String[] fqNames = new String[methods.size()];
        for (int i = 0; i < methods.size(); i++) {
            String id = (String) methods.get(i).get("id");
            indexOf.put(id, i);
            fqNames[i] = (String) methods.get(i).get("fqName");
        }
        List<Map<String, Object>> edges = q.raw(
                "MATCH (a:Method {projectId: $pid})-[:CALLS]->(b:Method {projectId: $pid}) RETURN a.id AS fromId, b.id AS toId",
                Map.of("pid", pid));
        List<int[]> edgePairs = new ArrayList<>(edges.size());
        for (Map<String, Object> e : edges) {
            Integer fi = indexOf.get(e.get("fromId"));
            Integer ti = indexOf.get(e.get("toId"));
            if (fi != null && ti != null) edgePairs.add(new int[]{fi, ti});
        }
        return new MethodGraph(fqNames, edgePairs, edges.size());
    }

    private DetectionResult detectCommunities(String algo, MethodGraph g) {
        switch (algo) {
            case "leiden" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detectLeiden(g.fqNames.length, g.edges);
                return new DetectionResult(r.community(), r.modularity());
            }
            case "louvain" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detect(g.fqNames.length, g.edges);
                return new DetectionResult(r.community(), r.modularity());
            }
            case "connected-components", "components", "union-find" -> {
                UnionFind uf = new UnionFind(g.fqNames.length);
                for (int[] p : g.edges) uf.union(p[0], p[1]);
                int[] community = new int[g.fqNames.length];
                Map<Integer, Integer> remap = new LinkedHashMap<>();
                int next = 0;
                for (int i = 0; i < g.fqNames.length; i++) {
                    int root = uf.find(i);
                    Integer mapped = remap.get(root);
                    if (mapped == null) { mapped = next++; remap.put(root, mapped); }
                    community[i] = mapped;
                }
                return new DetectionResult(community, Double.NaN);
            }
            default -> { return null; }
        }
    }

    private static Map<Integer, List<Integer>> groupByCommunity(int[] community) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < community.length; i++) {
            groups.computeIfAbsent(community[i], k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    private static List<Map<String, Object>> topCommunities(Map<Integer, List<Integer>> groups, String[] fqNames,
                                                              int minSize, int limit) {
        List<Map.Entry<Integer, List<Integer>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.<Map.Entry<Integer, List<Integer>>>comparingInt(en -> en.getValue().size()).reversed());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> en : ordered) {
            if (en.getValue().size() < minSize) continue;
            if (result.size() >= limit) break;
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < Math.min(en.getValue().size(), 10); i++) sample.add(fqNames[en.getValue().get(i)]);
            result.add(Map.of("size", en.getValue().size(), "sample", sample));
        }
        return result;
    }

    @Tool(name = "cv_flows",
            description = "Trace execution flows from entry points (REST handlers, main methods, @Test) through the call graph.")
    public Map<String, Object> flows(
            @ToolParam(description = "Entry-point kind: rest, main, test, or all (default all).", required = false) String kind,
            @ToolParam(description = "Max BFS depth from each entry (default 3).", required = false) Integer maxDepth,
            @ToolParam(description = "Max entry points to report (default 25).", required = false) Integer limit) {
        String k = kind == null || kind.isBlank() ? "all" : kind;
        int depth = maxDepth == null ? 3 : maxDepth;
        int lim = limit == null ? 25 : limit;
        String pid = project.projectId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depth", depth);

        if ("rest".equals(k) || "all".equals(k)) {
            out.put("rest", q.raw(
                    "MATCH (e:ApiEndpoint {projectId: $pid})-[:HANDLES]->(handler:Method) "
                            + "OPTIONAL MATCH (handler)-[:CALLS*1.." + Math.max(1, depth) + "]->(c:Method {projectId: $pid}) "
                            + "RETURN e.httpMethod AS method, e.path AS path, handler.fqName AS handler, "
                            + "collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", pid, "lim", lim)));
        }
        if ("main".equals(k) || "all".equals(k)) {
            out.put("main", q.raw(
                    "MATCH (m:Method {projectId: $pid, name: 'main'}) WHERE coalesce(m.isStatic, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + Math.max(1, depth) + "]->(c:Method {projectId: $pid}) "
                            + "RETURN m.fqName AS entry, collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", pid, "lim", lim)));
        }
        if ("test".equals(k) || "all".equals(k)) {
            out.put("test", q.raw(
                    "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isTest, false) = true "
                            + "OPTIONAL MATCH (m)-[:CALLS*1.." + Math.max(1, depth) + "]->(c:Method {projectId: $pid}) "
                            + "RETURN m.fqName AS entry, collect(DISTINCT c.fqName)[0..50] AS reaches LIMIT $lim",
                    Map.of("pid", pid, "lim", lim)));
        }
        return out;
    }

    @Tool(name = "cv_service_links",
            description = "Cross-service dependencies: outgoing HTTP clients, message-broker producers/consumers, exposed REST endpoints, and database tables touched.")
    public Map<String, Object> serviceLinks() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outgoingHttp", q.raw(
                "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                        + "WHERE callee.fqName =~ '(?i).*(RestTemplate|WebClient|HttpClient|FeignClient|OkHttpClient).*' "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls ORDER BY calls DESC LIMIT 50",
                Map.of("pid", pid)));
        out.put("outgoingMessaging", q.raw(
                "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                        + "WHERE callee.fqName =~ '(?i).*(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|SqsTemplate|SnsTemplate).*' "
                        + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls ORDER BY calls DESC LIMIT 50",
                Map.of("pid", pid)));
        out.put("incomingConsumers", q.raw(
                "MATCH (m:Method {projectId: $pid}) WHERE coalesce(m.isQueueListener, false) = true "
                        + "RETURN m.fqName AS handler LIMIT 100",
                Map.of("pid", pid)));
        out.put("restEndpoints", q.raw(
                "MATCH (e:ApiEndpoint {projectId: $pid})-[:HANDLES]->(m:Method) "
                        + "RETURN e.httpMethod AS method, e.path AS path, m.fqName AS handler ORDER BY path",
                Map.of("pid", pid)));
        out.put("tablesTouched", q.raw(
                "MATCH (n)-[r:READS_TABLE|WRITES_TABLE]->(t:Table {projectId: $pid}) "
                        + "RETURN t.name AS table, type(r) AS access, count(DISTINCT n) AS sources ORDER BY t.name",
                Map.of("pid", pid)));
        return out;
    }

    @Tool(name = "cv_audit",
            description = "Dependency vulnerability check via OSV.dev for every MavenDependency in the graph. Network call may take several seconds.")
    public Map<String, Object> audit() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> deps = q.raw(
                "MATCH (d:MavenDependency {projectId: $pid}) "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, d.version AS version "
                        + "ORDER BY groupId, artifactId",
                Map.of("pid", project.projectId()));
        out.put("dependenciesScanned", deps.size());

        if (deps.isEmpty()) {
            out.put("findings", List.of());
            return out;
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<Map<String, Object>> findings = new ArrayList<>();
        int errors = 0;
        for (Map<String, Object> d : deps) {
            String groupId = String.valueOf(d.get("groupId"));
            String artifactId = String.valueOf(d.get("artifactId"));
            String version = String.valueOf(d.get("version"));
            if (version == null || version.isBlank() || "null".equals(version) || version.contains("${")) continue;
            String coord = groupId + ":" + artifactId + ":" + version;
            try {
                String body = String.format(
                        "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"%s:%s\"},\"version\":\"%s\"}",
                        groupId, artifactId, version);
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(OSV_URL))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) { errors++; continue; }
                JsonNode root = JSON.readTree(resp.body());
                JsonNode vulns = root.path("vulns");
                if (!vulns.isArray()) continue;
                for (JsonNode v : vulns) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("dependency", coord);
                    row.put("id", v.path("id").asText());
                    row.put("summary", v.path("summary").asText(""));
                    findings.add(row);
                }
            } catch (Exception e) {
                errors++;
            }
        }
        out.put("networkErrors", errors);
        out.put("vulnerabilities", findings.size());
        out.put("findings", findings);
        return out;
    }

    private static Duration parseDuration(String s) {
        int n = 0;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            n = n * 10 + (s.charAt(i) - '0');
            i++;
        }
        if (i == 0) return Duration.ofHours(24);
        String unit = s.substring(i).toLowerCase();
        return switch (unit) {
            case "s", "sec", "secs" -> Duration.ofSeconds(n);
            case "m", "min", "mins" -> Duration.ofMinutes(n);
            case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(n);
            case "d", "day", "days" -> Duration.ofDays(n);
            default -> Duration.ofHours(24);
        };
    }
}
