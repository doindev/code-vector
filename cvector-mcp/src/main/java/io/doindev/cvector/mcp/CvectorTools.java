package io.doindev.cvector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.doindev.cvector.core.util.UnionFind;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigResolver;
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

/**
 * MCP tools exposed to AI agents. All read paths route through {@link GraphStore}, so the tool
 * surface works against either Neo4j or the embedded KuzuDB store with no backend branching.
 */
public class CvectorTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OSV_URL = "https://api.osv.dev/v1/query";

    private final GraphStore store;
    private final McpServerConfig.McpActiveProject project;
    private final CvectorConfigService configService;

    public CvectorTools(GraphStore store, McpServerConfig.McpActiveProject project, CvectorConfigService configService) {
        this.store = store;
        this.project = project;
        this.configService = configService;
    }

    @Tool(name = "cv_stats", description = "Graph statistics for the active cvector project: node counts by label and edge counts by type.")
    public Map<String, Object> stats() {
        return Map.of(
                "project", project.name(),
                "projectId", project.projectId(),
                "nodes", store.nodeCounts(project.projectId()),
                "edges", store.edgeCounts(project.projectId())
        );
    }

    @Tool(name = "cv_health", description = "Health check: backend connectivity, graph size, last scan commit, and core counts.")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        boolean ok = store.ping();
        out.put("backend", Map.of("type", store.backend(), "ok", ok, "uri", store.displayUri()));
        out.put("nodes", store.nodeCounts(project.projectId()));
        out.put("edges", store.edgeCounts(project.projectId()));
        out.put("lastScan", store.projectMeta(project.projectId()));
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
        List<Map<String, Object>> rows = store.searchByName(project.projectId(), query, label, lim);
        return Map.of("query", query, "count", rows.size(), "results", rows);
    }

    @Tool(name = "cv_explain",
            description = "Full context for a symbol: type, file:line, callers (incoming CALLS), callees (outgoing CALLS).")
    public Map<String, Object> explain(@ToolParam(description = "Symbol name (fully-qualified or last segment).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
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
            out.put("callers", store.callers(project.projectId(), id));
            out.put("callees", store.callees(project.projectId(), id));
        }
        if (hit.get("fileId") != null) {
            out.put("file", store.fileOf(project.projectId(), (String) hit.get("fileId")));
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
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("depth", d);
        out.put("impacted", store.impactDownstream(project.projectId(), (String) hit.get("id"), d));
        return out;
    }

    @Tool(name = "cv_context",
            description = "Everything a class or method contains and references: methods, called methods, fields, importing files.")
    public Map<String, Object> context(@ToolParam(description = "Symbol name (Class or Method).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("contains", store.contains(project.projectId(), id, 200));
        out.put("callers", store.callers(project.projectId(), id));
        out.put("callees", store.callees(project.projectId(), id));
        return out;
    }

    @Tool(name = "cv_rename",
            description = "Graph-aware rename impact: all callers, references, importing files, and the target's definition for a symbol about to be renamed.")
    public Map<String, Object> rename(@ToolParam(description = "Symbol to rename (fully-qualified or last segment).") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("callers", store.callers(project.projectId(), id));
        out.put("references", store.referencingNodes(project.projectId(), id, 500));
        out.put("importingFiles", store.importingFiles(project.projectId(), id, 200));
        return out;
    }

    @Tool(name = "cv_changes",
            description = "Recently-ingested graph nodes within a time window (e.g. '24h', '7d').")
    public Map<String, Object> changes(
            @ToolParam(description = "Time window: 24h, 7d, 30m, etc. (default 24h).", required = false) String since,
            @ToolParam(description = "Max rows (default 50).", required = false) Integer limit) {
        String window = since == null || since.isBlank() ? "24h" : since;
        int lim = limit == null ? 50 : limit;
        List<Map<String, Object>> rows = store.recentlyChanged(project.projectId(), parseDuration(window), lim);
        return Map.of("since", window, "count", rows.size(), "changes", rows);
    }

    @Tool(name = "cv_onboard", description = "Full codebase briefing: project info, languages, top classes, REST endpoints, tables, config keys, dependencies, call-graph hubs.")
    public Map<String, Object> onboard() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("name", project.name(), "projectId", pid, "rootPath", project.rootPath()));
        out.put("nodes", store.nodeCounts(pid));
        out.put("edges", store.edgeCounts(pid));
        out.put("dependencies", store.mavenDependencies(pid));
        out.putAll(store.onboardSummary(pid));
        return out;
    }

    @Tool(name = "cv_test_impact",
            description = "Find test methods (@Test-annotated) that transitively reach a given symbol.")
    public Map<String, Object> testImpact(
            @ToolParam(description = "Symbol name.") String symbol,
            @ToolParam(description = "Max traversal depth (default 5).", required = false) Integer depth) {
        int d = depth == null ? 5 : depth;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("tests", store.testReach(project.projectId(), (String) hit.get("id"), d));
        return out;
    }

    @Tool(name = "cv_rules", description = "Run the cvector rules engine and return violations by rule. Layers defaults → .cvector/rules.yml → workspace settings.json rules → per-project rules.")
    public Map<String, Object> rules() {
        Path rulesYml = Paths.get(project.rootPath(), ".cvector", "rules.yml");
        // Load settings.json fresh per call so live edits (e.g. raising a threshold) take effect
        // without restarting the MCP server.
        CvectorConfig workspace = loadWorkspaceConfigSilently();
        String projectKey = workspace != null ? workspace.activeProject() : null;
        RulesConfig cfg = RulesConfigResolver.resolve(workspace, projectKey, rulesYml);
        RulesEngine.Report report = new RulesEngine(project.projectId(), store, cfg).run();

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

    /**
     * Load the workspace's settings.json from the project's root, silently swallowing any I/O
     * error. The rules engine treats a {@code null} return as "no workspace policy" — it falls
     * through to the legacy rules.yml + defaults so a missing or broken settings file never
     * brings cv_rules down.
     */
    private CvectorConfig loadWorkspaceConfigSilently() {
        if (configService == null) return null;
        try {
            Path root = Paths.get(project.rootPath());
            if (!configService.exists(root)) return null;
            return configService.load(root);
        } catch (Exception e) {
            return null;
        }
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

        GraphStore.MethodCallGraph g = store.methodCallGraph(project.projectId());
        if (g.fqNames().length == 0) return Map.of("communities", List.of(), "totalMethods", 0);

        DetectionResult det = detectCommunities(algo, g);
        if (det == null) {
            return Map.of(
                    "error", "unknown algorithm: " + algo,
                    "validAlgorithms", List.of("leiden", "louvain", "connected-components"));
        }

        Map<Integer, List<Integer>> groups = groupByCommunity(det.community);
        List<Map<String, Object>> result = topCommunities(groups, g.fqNames(), min, lim);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", algo);
        out.put("totalMethods", g.fqNames().length);
        out.put("totalEdges", g.edges().size());
        out.put("communitiesFound", groups.size());
        if (!Double.isNaN(det.modularity)) out.put("modularity", det.modularity);
        out.put("communities", result);
        return out;
    }

    private record DetectionResult(int[] community, double modularity) {}

    private DetectionResult detectCommunities(String algo, GraphStore.MethodCallGraph g) {
        switch (algo) {
            case "leiden" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detectLeiden(g.fqNames().length, g.edges());
                return new DetectionResult(r.community(), r.modularity());
            }
            case "louvain" -> {
                LouvainCommunityDetector.Result r = LouvainCommunityDetector.detect(g.fqNames().length, g.edges());
                return new DetectionResult(r.community(), r.modularity());
            }
            case "connected-components", "components", "union-find" -> {
                UnionFind uf = new UnionFind(g.fqNames().length);
                for (int[] p : g.edges()) uf.union(p[0], p[1]);
                int[] community = new int[g.fqNames().length];
                Map<Integer, Integer> remap = new LinkedHashMap<>();
                int next = 0;
                for (int i = 0; i < g.fqNames().length; i++) {
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
        int depth = maxDepth == null ? 3 : maxDepth;
        int lim = limit == null ? 25 : limit;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depth", depth);
        out.putAll(store.traceFlows(project.projectId(), kind, depth, lim));
        return out;
    }

    @Tool(name = "cv_service_links",
            description = "Cross-service dependencies: outgoing HTTP clients, message-broker producers/consumers, exposed REST endpoints, and database tables touched.")
    public Map<String, Object> serviceLinks() {
        return new LinkedHashMap<>(store.serviceLinks(project.projectId()));
    }

    @Tool(name = "cv_trace",
            description = "Shortest dependency chain between two symbols over CALLS edges (names only). Returns a flat fqName chain ordered from source to target.")
    public Map<String, Object> trace(
            @ToolParam(description = "Source symbol (fully-qualified or last segment).") String from,
            @ToolParam(description = "Target symbol (fully-qualified or last segment).") String to,
            @ToolParam(description = "Max BFS depth (default 6, hard cap 12).", required = false) Integer depth) {
        return tracePath(from, to, depth, /*detailed=*/ false);
    }

    @Tool(name = "cv_path",
            description = "Detailed shortest path between two symbols: each hop's node label, fqName, and the edge type leading to the next node.")
    public Map<String, Object> path(
            @ToolParam(description = "Source symbol.") String from,
            @ToolParam(description = "Target symbol.") String to,
            @ToolParam(description = "Max BFS depth (default 6, hard cap 12).", required = false) Integer depth) {
        return tracePath(from, to, depth, /*detailed=*/ true);
    }

    private Map<String, Object> tracePath(String from, String to, Integer depth, boolean detailed) {
        int d = depth == null ? 6 : Math.max(1, Math.min(depth, 12));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("depth", d);
        List<Map<String, Object>> sources = store.findSymbol(project.projectId(), from);
        List<Map<String, Object>> targets = store.findSymbol(project.projectId(), to);
        if (sources.isEmpty() || targets.isEmpty()) {
            out.put("found", false);
            out.put("reason", sources.isEmpty() ? "source-not-found" : "target-not-found");
            return out;
        }
        Map<String, Object> source = sources.get(0);
        Map<String, Object> target = targets.get(0);
        out.put("source", source);
        out.put("target", target);
        Map<String, Object> p = store.shortestPath(project.projectId(),
                (String) source.get("id"), (String) target.get("id"), d);
        boolean found = Boolean.TRUE.equals(p.get("found"));
        out.put("found", found);
        if (!found) {
            out.put("reason", "no-path");
            return out;
        }
        out.put("pathDepth", p.get("depth"));
        if (detailed) {
            out.put("nodes", p.get("nodes"));
            out.put("edges", p.get("edges"));
        } else {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) p.get("nodes");
            List<Object> chain = new ArrayList<>(nodes.size());
            for (Map<String, Object> n : nodes) chain.add(n.getOrDefault("fqName", n.getOrDefault("name", "")));
            out.put("chain", chain);
        }
        return out;
    }

    @Tool(name = "cv_db_impact",
            description = "Database blast radius: methods that read from or write to a given table (optionally narrowed to a column).")
    public Map<String, Object> dbImpact(
            @ToolParam(description = "Table name.") String table,
            @ToolParam(description = "Optional column name to narrow the result.", required = false) String column) {
        Map<String, List<Map<String, Object>>> impact = store.dbImpact(project.projectId(), table, column);
        List<Map<String, Object>> readers = impact.getOrDefault("readers", List.of());
        List<Map<String, Object>> writers = impact.getOrDefault("writers", List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("column", column);
        out.put("readerCount", readers.size());
        out.put("writerCount", writers.size());
        out.put("readers", readers);
        out.put("writers", writers);
        return out;
    }

    @Tool(name = "cv_guard",
            description = "Quality gate snapshot: per-rule pass/fail and the worst-offender breakdown (god files, dead code, etc).")
    public Map<String, Object> guard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.putAll(store.guardSummary(project.projectId()));
        return out;
    }

    @Tool(name = "cv_diff_start",
            description = "Start an async git diff between two commits (or refs like HEAD~3 / branch names). Returns immediately with {ok, shaA, shaB}; poll cv_diff_status until running=false. The underlying command checks out two worktrees and runs full scans, so it can take minutes — only one diff may run at a time.")
    public Map<String, Object> diffStart(
            @ToolParam(description = "Base commit / ref (older).") String shaA,
            @ToolParam(description = "Target commit / ref (newer).") String shaB,
            @ToolParam(description = "Also diff CALLS edges (heavier query).", required = false) Boolean includeCalls,
            @ToolParam(description = "Keep snapshot data after diff (default false).", required = false) Boolean keep) {
        boolean inc = includeCalls != null && includeCalls;
        boolean k = keep != null && keep;
        return CvectorDiffSubprocess.start(shaA, shaB, inc, k);
    }

    @Tool(name = "cv_diff_status",
            description = "Poll the status of the most recent cv_diff_start. Returns {running, startedAt, elapsedMillis, shaA, shaB, partialOutput, last:{output, exitCode}} when a diff is in flight or has completed.")
    public Map<String, Object> diffStatus() {
        return CvectorDiffSubprocess.status();
    }

    @Tool(name = "cv_wiki",
            description = "Structured documentation snapshot: file index, top classes, REST endpoints, tables, config keys, dependencies.")
    public Map<String, Object> wiki() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", pid);
        out.put("projectName", project.name());
        Map<String, Long> nodes = store.nodeCounts(pid);
        Map<String, Long> edges = store.edgeCounts(pid);
        out.put("totals", Map.of(
                "nodes", nodes.values().stream().mapToLong(Long::longValue).sum(),
                "edges", edges.values().stream().mapToLong(Long::longValue).sum()
        ));
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("files", store.fileInventory(pid));
        out.putAll(store.onboardSummary(pid));
        out.putAll(store.infrastructureSummary(pid));
        out.put("dependencies", store.mavenDependencies(pid));
        return out;
    }

    @Tool(name = "cv_audit",
            description = "Dependency vulnerability check via OSV.dev for every MavenDependency in the graph. Network call may take several seconds.")
    public Map<String, Object> audit() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> deps = store.mavenDependencies(project.projectId());
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
            Object versionObj = d.get("version");
            String version = versionObj == null ? null : String.valueOf(versionObj);
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
