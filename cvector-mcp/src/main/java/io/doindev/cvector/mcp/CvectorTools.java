package io.doindev.cvector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfig.ProjectEntry;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.core.util.LouvainCommunityDetector;
import io.doindev.cvector.core.util.UnionFind;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigResolver;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Violation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tools exposed to AI agents. All read paths route through {@link GraphStore}, so the tool
 * surface works against either Neo4j or the embedded KuzuDB store with no backend branching.
 *
 * <p><b>Project parameter.</b> Every read tool accepts an optional {@code project} argument
 * (name, UUID, or rootPath). When omitted, the tool falls back to the workspace's
 * {@code activeProject} field in {@code settings.json} — set via {@code cv_set_default_project}
 * or {@code cvector project switch <name>}. Tools that support cross-project queries (search,
 * stats, changes, communities, service_links, health) accept the literal {@code "*"} to scope
 * across every project.
 *
 * <p><b>Project context in responses.</b> Every response that targets a specific project
 * carries a top-level {@code project} block with {@code projectId}, {@code name},
 * {@code rootPath}, and {@code isolated} so callers (human or AI) can confirm which project
 * the result came from — defending against silent default mis-routing.
 *
 * <p><b>Lifecycle tools</b> (cv_list_projects, cv_find_project, cv_add_project,
 * cv_scan_project, cv_onboard_project, cv_remove_project, cv_set_default_project) don't
 * require a {@code project} argument — they operate on the workspace catalog or take a
 * name+rootPath instead.
 */
public class CvectorTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OSV_URL = "https://api.osv.dev/v1/query";

    private final GraphStore store;
    private final ProjectResolver projects;

    public CvectorTools(GraphStore store, ProjectResolver projects) {
        this.store = store;
        this.projects = projects;
    }

    // ===========================================================================================
    //  Lifecycle / discovery — no project arg required (these are what an agent calls first)
    // ===========================================================================================

    @Tool(name = "cv_list_projects",
            description = "List every project in the workspace with full metadata: name, projectId (UUID), rootPath, isolated flag, sharedDb flag, active flag, backend, total node/edge counts, last scan commit + timestamp. The agent's primary discovery tool — call this before any per-project query to find the projectId you need.")
    public List<Map<String, Object>> listProjects() {
        return projects.listProjects();
    }

    @Tool(name = "cv_find_project",
            description = "Look up a project by name, UUID, or directory path. Path matching tolerates input that's a descendant of a project's rootPath (e.g. /work/repo/src/main matches a project rooted at /work/repo). Returns {found: true, project: {...}} on a hit or {found: false, candidates: [...names]} on miss.")
    public Map<String, Object> findProject(
            @ToolParam(description = "Project name, UUID, or absolute directory path.") String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        ProjectEntry hit = projects.find(query);
        if (hit != null) {
            out.put("found", true);
            out.put("project", projectContext(hit));
            return out;
        }
        out.put("found", false);
        List<String> candidates = new ArrayList<>();
        for (ProjectEntry e : projects.loadConfig().projects().values()) candidates.add(e.name());
        out.put("candidates", candidates);
        return out;
    }

    @Tool(name = "cv_set_default_project",
            description = "Set the workspace's default (active) project. Subsequent tool calls that omit the `project` argument will resolve to this one. Equivalent to running `cvector project switch <name>` from the CLI. Returns the new + previous default.")
    public Map<String, Object> setDefaultProject(
            @ToolParam(description = "Project name, UUID, or rootPath to make the new default.") String project) {
        ProjectEntry target = projects.resolve(project);
        CvectorConfig cfg = projects.loadConfig();
        // Find the name (map key) for the resolved projectId — could be different from input
        // if the caller passed a UUID or path.
        String newActive = null;
        for (Map.Entry<String, ProjectEntry> en : cfg.projects().entrySet()) {
            if (target.projectId().equals(en.getValue().projectId())) {
                newActive = en.getKey();
                break;
            }
        }
        String previous = cfg.activeProject();
        CvectorConfig next = new CvectorConfig(
                newActive, cfg.projects(), cfg.neo4j(), cfg.backend(), cfg.rest(), cfg.mcp(),
                cfg.docker(), cfg.rules(), cfg.kuzu());
        try {
            projects.service().save(projects.configRoot(), next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("previousDefault", previous);
        out.put("newDefault", newActive);
        out.put("project", projectContext(target));
        return out;
    }

    @Tool(name = "cv_add_project",
            description = "Register a new project in the workspace without scanning it. Writes the entry to settings.json with a fresh UUID. Rejects a rootPath that overlaps an existing project (sub-directory of an existing project, or a directory that would contain one). Use cv_scan_project (or cv_onboard_project for the combined flow) afterwards to populate the graph.")
    public Map<String, Object> addProject(
            @ToolParam(description = "Project name (becomes the lookup key — must be unique within the workspace).") String name,
            @ToolParam(description = "Absolute directory path of the codebase root. Must NOT be a sub-directory of an existing project's rootPath and must NOT contain one.") String rootPath,
            @ToolParam(description = "If true, give this project its own Kuzu DB directory instead of sharing the workspace DB. Default false.", required = false) Boolean isolated) {
        CvectorConfig cfg = projects.loadConfig();
        if (cfg.projects().containsKey(name)) {
            throw new IllegalArgumentException("project '" + name + "' already exists. Use cv_find_project to look it up, or pick a different name.");
        }
        Path normalised = Paths.get(rootPath).toAbsolutePath().normalize();
        projects.validateAddable(normalised);
        String projectId = UUID.randomUUID().toString();
        Map<String, ProjectEntry> updated = new LinkedHashMap<>(cfg.projects());
        Boolean isolatedFlag = Boolean.TRUE.equals(isolated) ? Boolean.TRUE : null;
        ProjectEntry created = new ProjectEntry(projectId, name, normalised.toString(), null, isolatedFlag);
        updated.put(name, created);
        CvectorConfig next = new CvectorConfig(
                cfg.activeProject() != null ? cfg.activeProject() : name,
                updated, cfg.neo4j(), cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker(),
                cfg.rules(), cfg.kuzu());
        try {
            projects.service().save(projects.configRoot(), next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("project", projectContext(created));
        out.put("nextStep", "Call cv_scan_project to populate the graph, or cv_onboard_project for an end-to-end add+scan+brief.");
        return out;
    }

    @Tool(name = "cv_remove_project",
            description = "Remove a project from the workspace AND delete its graph data. Requires confirm=true to actually run; without it the tool returns a dry-run summary so the agent can show the user what would be deleted.")
    public Map<String, Object> removeProject(
            @ToolParam(description = "Project name, UUID, or rootPath. If omitted, falls back to the workspace's default project.", required = false) String project,
            @ToolParam(description = "Must be true to actually delete. Without this the tool reports what would be removed.", required = false) Boolean confirm) {
        ProjectEntry target = projects.resolveOrDefault(project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectContext(target));
        if (!Boolean.TRUE.equals(confirm)) {
            try {
                Map<String, Long> nodes = store.nodeCounts(target.projectId());
                out.put("nodesPendingDelete", nodes.values().stream().mapToLong(Long::longValue).sum());
            } catch (RuntimeException ignored) {
                out.put("nodesPendingDelete", null);
            }
            out.put("dryRun", true);
            out.put("hint", "Pass confirm=true to actually remove this project.");
            return out;
        }
        try {
            store.deleteProjectSubtree(target.projectId());
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to delete graph data for project " + target.name() + ": " + e.getMessage(), e);
        }
        CvectorConfig cfg = projects.loadConfig();
        Map<String, ProjectEntry> updated = new LinkedHashMap<>(cfg.projects());
        updated.remove(target.name());
        String nextActive = target.name().equals(cfg.activeProject())
                ? (updated.isEmpty() ? null : updated.keySet().iterator().next())
                : cfg.activeProject();
        CvectorConfig next = new CvectorConfig(
                nextActive, updated, cfg.neo4j(), cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker(),
                cfg.rules(), cfg.kuzu());
        try {
            projects.service().save(projects.configRoot(), next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.put("removed", true);
        out.put("newDefault", nextActive);
        return out;
    }

    @Tool(name = "cv_scan_project",
            description = "Scan an existing registered project to (re)populate its graph data. Synchronous — returns when the scan completes with node/edge counts and elapsed time. Idempotent: re-scans use content-hash skipping for unchanged files.")
    public Map<String, Object> scanProject(
            @ToolParam(description = "Project name, UUID, or rootPath. If omitted, falls back to the workspace's default project.", required = false) String project) {
        ProjectEntry target = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(target);
        out.putAll(CvectorScanService.scan(projects, target));
        return out;
    }

    @Tool(name = "cv_onboard_project",
            description = "Convenience: register a new project AND scan it AND return the codebase briefing in one call. Equivalent to cv_add_project + cv_scan_project + cv_onboard. Use this when an agent is asked to onboard a brand-new codebase.")
    public Map<String, Object> onboardProject(
            @ToolParam(description = "Project name.") String name,
            @ToolParam(description = "Absolute directory path of the codebase root.") String rootPath,
            @ToolParam(description = "Give this project its own Kuzu DB directory. Default false.", required = false) Boolean isolated) {
        Map<String, Object> addResult = addProject(name, rootPath, isolated);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) addResult.get("project");
        String projectId = (String) created.get("projectId");
        Map<String, Object> scanResult = scanProject(projectId);
        Map<String, Object> briefing = onboard(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("project", created);
        out.put("scan", scanResult);
        out.put("briefing", briefing);
        return out;
    }

    // ===========================================================================================
    //  Read tools — every one accepts an OPTIONAL `project` arg with default fallback.
    //  Wildcard "*" supported where cross-project queries make sense.
    // ===========================================================================================

    @Tool(name = "cv_stats",
            description = "Graph statistics: node counts by label and edge counts by type. If `project` is omitted, the workspace's default project is used. Accepts '*' to aggregate across every project.")
    public Map<String, Object> stats(
            @ToolParam(description = "Project name, UUID, rootPath, or '*' for all projects. If omitted, uses the workspace's active project.", required = false) String project) {
        if (projects.isWildcard(project)) {
            return aggregateAcrossProjects(p -> {
                Map<String, Object> row = newResponse(p);
                row.put("nodes", store.nodeCounts(p.projectId()));
                row.put("edges", store.edgeCounts(p.projectId()));
                return row;
            });
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        out.put("nodes", store.nodeCounts(p.projectId()));
        out.put("edges", store.edgeCounts(p.projectId()));
        return out;
    }

    @Tool(name = "cv_health",
            description = "Health check: backend connectivity, graph size, last scan commit. If `project` is omitted the default is used. Accepts '*' for every project.")
    public Map<String, Object> health(
            @ToolParam(description = "Project name, UUID, rootPath, or '*'. If omitted, uses the workspace's active project.", required = false) String project) {
        if (projects.isWildcard(project)) {
            return aggregateAcrossProjects(this::healthRowFor);
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        return healthRowFor(p);
    }

    private Map<String, Object> healthRowFor(ProjectEntry p) {
        Map<String, Object> out = newResponse(p);
        boolean ok = store.ping();
        out.put("backend", Map.of("type", store.backend(), "ok", ok, "uri", store.displayUri()));
        out.put("nodes", store.nodeCounts(p.projectId()));
        out.put("edges", store.edgeCounts(p.projectId()));
        out.put("lastScan", store.projectMeta(p.projectId()));
        return out;
    }

    @Tool(name = "cv_search",
            description = "Search graph nodes by substring of name or fqName. If `project` is omitted, the workspace's default is used. Accepts '*' to search every project at once (results carry projectId).")
    public Map<String, Object> search(
            @ToolParam(description = "Project name, UUID, rootPath, or '*'. If omitted, uses the workspace's active project.", required = false) String project,
            @ToolParam(description = "Search substring; supports * wildcards.") String query,
            @ToolParam(description = "Max results (default 25 per project).", required = false) Integer limit,
            @ToolParam(description = "Restrict to a single node label (e.g. 'Method').", required = false) String label) {
        int lim = limit == null ? 25 : limit;
        if (projects.isWildcard(project)) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (ProjectEntry p : projects.loadConfig().projects().values()) {
                List<Map<String, Object>> rows = store.searchByName(p.projectId(), query, label, lim);
                for (Map<String, Object> r : rows) {
                    Map<String, Object> tagged = new LinkedHashMap<>(r);
                    tagged.put("project", projectContext(p));
                    all.add(tagged);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("crossProject", true);
            out.put("query", query);
            out.put("count", all.size());
            out.put("results", all);
            return out;
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        out.put("query", query);
        List<Map<String, Object>> rows = store.searchByName(p.projectId(), query, label, lim);
        out.put("count", rows.size());
        out.put("results", rows);
        return out;
    }

    @Tool(name = "cv_explain",
            description = "Full context for a symbol: type, file:line, callers (incoming CALLS), callees (outgoing CALLS). If `project` is omitted, the workspace's default is used.")
    public Map<String, Object> explain(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Symbol name (fully-qualified or last segment).") String symbol) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> matches = store.findSymbol(p.projectId(), symbol);
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
            out.put("callers", store.callers(p.projectId(), id));
            out.put("callees", store.callees(p.projectId(), id));
        }
        if (hit.get("fileId") != null) {
            out.put("file", store.fileOf(p.projectId(), (String) hit.get("fileId")));
        }
        return out;
    }

    @Tool(name = "cv_impact",
            description = "Downstream impact of changing a symbol: BFS via CALLS/REFERENCES edges. If `project` is omitted, the workspace's default is used.")
    public Map<String, Object> impact(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Symbol name.") String symbol,
            @ToolParam(description = "Max traversal depth (default 3).", required = false) Integer depth) {
        ProjectEntry p = projects.resolveOrDefault(project);
        int d = depth == null ? 3 : depth;
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> matches = store.findSymbol(p.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("depth", d);
        out.put("impacted", store.impactDownstream(p.projectId(), (String) hit.get("id"), d));
        return out;
    }

    @Tool(name = "cv_context",
            description = "Everything a class or method contains and references: methods, called methods, fields, importing files. If `project` is omitted, the default is used.")
    public Map<String, Object> context(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Symbol name (Class or Method).") String symbol) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> matches = store.findSymbol(p.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("contains", store.contains(p.projectId(), id, 200));
        out.put("callers", store.callers(p.projectId(), id));
        out.put("callees", store.callees(p.projectId(), id));
        return out;
    }

    @Tool(name = "cv_rename",
            description = "Graph-aware rename impact: all callers, references, importing files, and the target's definition. If `project` is omitted, the default is used.")
    public Map<String, Object> rename(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Symbol to rename.") String symbol) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> matches = store.findSymbol(p.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("callers", store.callers(p.projectId(), id));
        out.put("references", store.referencingNodes(p.projectId(), id, 500));
        out.put("importingFiles", store.importingFiles(p.projectId(), id, 200));
        return out;
    }

    @Tool(name = "cv_changes",
            description = "Recently-ingested graph nodes within a time window. If `project` is omitted, the default is used. Accepts '*' for cross-project recent activity.")
    public Map<String, Object> changes(
            @ToolParam(description = "Project name, UUID, rootPath, or '*'. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Time window: 24h, 7d, 30m, etc. (default 24h).", required = false) String since,
            @ToolParam(description = "Max rows per project (default 50).", required = false) Integer limit) {
        String window = since == null || since.isBlank() ? "24h" : since;
        int lim = limit == null ? 50 : limit;
        if (projects.isWildcard(project)) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (ProjectEntry p : projects.loadConfig().projects().values()) {
                List<Map<String, Object>> rows = store.recentlyChanged(p.projectId(), parseDuration(window), lim);
                for (Map<String, Object> r : rows) {
                    Map<String, Object> tagged = new LinkedHashMap<>(r);
                    tagged.put("project", projectContext(p));
                    all.add(tagged);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("crossProject", true);
            out.put("since", window);
            out.put("count", all.size());
            out.put("changes", all);
            return out;
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        out.put("since", window);
        List<Map<String, Object>> rows = store.recentlyChanged(p.projectId(), parseDuration(window), lim);
        out.put("count", rows.size());
        out.put("changes", rows);
        return out;
    }

    @Tool(name = "cv_onboard",
            description = "Full codebase briefing for a project: languages, top classes, REST endpoints, tables, config keys, dependencies, call-graph hubs. If `project` is omitted, the default is used.")
    public Map<String, Object> onboard(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project) {
        ProjectEntry p = projects.resolveOrDefault(project);
        String pid = p.projectId();
        Map<String, Object> out = newResponse(p);
        out.put("nodes", store.nodeCounts(pid));
        out.put("edges", store.edgeCounts(pid));
        out.put("dependencies", store.mavenDependencies(pid));
        out.putAll(store.onboardSummary(pid));
        return out;
    }

    @Tool(name = "cv_test_impact",
            description = "Find test methods (@Test-annotated) that transitively reach a given symbol. If `project` is omitted, the default is used.")
    public Map<String, Object> testImpact(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Symbol name.") String symbol,
            @ToolParam(description = "Max traversal depth (default 5).", required = false) Integer depth) {
        ProjectEntry p = projects.resolveOrDefault(project);
        int d = depth == null ? 5 : depth;
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> matches = store.findSymbol(p.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("tests", store.testReach(p.projectId(), (String) hit.get("id"), d));
        return out;
    }

    @Tool(name = "cv_rules",
            description = "Run the cvector rules engine and return violations by rule. If `project` is omitted, the default is used.")
    public Map<String, Object> rules(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Path rulesYml = Paths.get(p.rootPath(), ".cvector", "rules.yml");
        CvectorConfig workspace = projects.loadConfig();
        String projectKey = null;
        for (Map.Entry<String, ProjectEntry> en : workspace.projects().entrySet()) {
            if (p.projectId().equals(en.getValue().projectId())) { projectKey = en.getKey(); break; }
        }
        RulesConfig cfg = RulesConfigResolver.resolve(workspace, projectKey, rulesYml);
        RulesEngine.Report report = new RulesEngine(p.projectId(), store, cfg).run();

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
        Map<String, Object> out = newResponse(p);
        out.put("hasErrors", report.hasErrors());
        out.put("totals", report.bySeverity());
        out.put("totalViolations", report.totalViolations());
        out.put("runs", runs);
        return out;
    }

    @Tool(name = "cv_communities",
            description = "Detect functional clusters in the call graph. If `project` is omitted, the default is used. Accepts '*' to compute per-project across the workspace.")
    public Map<String, Object> communities(
            @ToolParam(description = "Project name, UUID, rootPath, or '*'. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Algorithm: leiden (default), louvain, or connected-components.", required = false) String algorithm,
            @ToolParam(description = "Minimum community size (default 3).", required = false) Integer minSize,
            @ToolParam(description = "Max communities to return (default 10).", required = false) Integer limit) {
        String algo = (algorithm == null || algorithm.isBlank()) ? "leiden" : algorithm.toLowerCase();
        int min = minSize == null ? 3 : minSize;
        int lim = limit == null ? 10 : limit;
        if (projects.isWildcard(project)) {
            List<Map<String, Object>> perProject = new ArrayList<>();
            for (ProjectEntry p : projects.loadConfig().projects().values()) {
                Map<String, Object> r = communitiesForProject(p, algo, min, lim);
                perProject.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("crossProject", true);
            out.put("perProject", perProject);
            return out;
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        return communitiesForProject(p, algo, min, lim);
    }

    private Map<String, Object> communitiesForProject(ProjectEntry p, String algo, int min, int lim) {
        Map<String, Object> out = newResponse(p);
        GraphStore.MethodCallGraph g = store.methodCallGraph(p.projectId());
        if (g.fqNames().length == 0) {
            out.put("communities", List.of());
            out.put("totalMethods", 0);
            return out;
        }
        DetectionResult det = detectCommunities(algo, g);
        if (det == null) {
            out.put("error", "unknown algorithm: " + algo);
            out.put("validAlgorithms", List.of("leiden", "louvain", "connected-components"));
            return out;
        }
        Map<Integer, List<Integer>> groups = groupByCommunity(det.community);
        List<Map<String, Object>> result = topCommunities(groups, g.fqNames(), min, lim);
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
            description = "Trace execution flows from entry points (REST handlers, main methods, @Test) through the call graph. If `project` is omitted, the default is used.")
    public Map<String, Object> flows(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Entry-point kind: rest, main, test, or all (default all).", required = false) String kind,
            @ToolParam(description = "Max BFS depth from each entry (default 3).", required = false) Integer maxDepth,
            @ToolParam(description = "Max entry points to report (default 25).", required = false) Integer limit) {
        ProjectEntry p = projects.resolveOrDefault(project);
        int depth = maxDepth == null ? 3 : maxDepth;
        int lim = limit == null ? 25 : limit;
        Map<String, Object> out = newResponse(p);
        out.put("depth", depth);
        out.putAll(store.traceFlows(p.projectId(), kind, depth, lim));
        return out;
    }

    @Tool(name = "cv_service_links",
            description = "Cross-service dependencies. If `project` is omitted, the default is used. Accepts '*' to compute per-project across the workspace.")
    public Map<String, Object> serviceLinks(
            @ToolParam(description = "Project name, UUID, rootPath, or '*'. Omit to use the default.", required = false) String project) {
        if (projects.isWildcard(project)) {
            List<Map<String, Object>> perProject = new ArrayList<>();
            for (ProjectEntry p : projects.loadConfig().projects().values()) {
                Map<String, Object> row = newResponse(p);
                row.putAll(store.serviceLinks(p.projectId()));
                perProject.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("crossProject", true);
            out.put("perProject", perProject);
            return out;
        }
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        out.putAll(store.serviceLinks(p.projectId()));
        return out;
    }

    @Tool(name = "cv_trace",
            description = "Shortest dependency chain between two symbols over CALLS edges. If `project` is omitted, the default is used.")
    public Map<String, Object> trace(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Source symbol.") String from,
            @ToolParam(description = "Target symbol.") String to,
            @ToolParam(description = "Max BFS depth (default 6, hard cap 12).", required = false) Integer depth) {
        ProjectEntry p = projects.resolveOrDefault(project);
        return tracePath(p, from, to, depth, /*detailed=*/ false);
    }

    @Tool(name = "cv_path",
            description = "Detailed shortest path between two symbols. If `project` is omitted, the default is used.")
    public Map<String, Object> path(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Source symbol.") String from,
            @ToolParam(description = "Target symbol.") String to,
            @ToolParam(description = "Max BFS depth (default 6, hard cap 12).", required = false) Integer depth) {
        ProjectEntry p = projects.resolveOrDefault(project);
        return tracePath(p, from, to, depth, /*detailed=*/ true);
    }

    private Map<String, Object> tracePath(ProjectEntry p, String from, String to, Integer depth, boolean detailed) {
        int d = depth == null ? 6 : Math.max(1, Math.min(depth, 12));
        Map<String, Object> out = newResponse(p);
        out.put("from", from);
        out.put("to", to);
        out.put("depth", d);
        List<Map<String, Object>> sources = store.findSymbol(p.projectId(), from);
        List<Map<String, Object>> targets = store.findSymbol(p.projectId(), to);
        if (sources.isEmpty() || targets.isEmpty()) {
            out.put("found", false);
            out.put("reason", sources.isEmpty() ? "source-not-found" : "target-not-found");
            return out;
        }
        Map<String, Object> source = sources.get(0);
        Map<String, Object> target = targets.get(0);
        out.put("source", source);
        out.put("target", target);
        Map<String, Object> pp = store.shortestPath(p.projectId(),
                (String) source.get("id"), (String) target.get("id"), d);
        boolean found = Boolean.TRUE.equals(pp.get("found"));
        out.put("found", found);
        if (!found) {
            out.put("reason", "no-path");
            return out;
        }
        out.put("pathDepth", pp.get("depth"));
        if (detailed) {
            out.put("nodes", pp.get("nodes"));
            out.put("edges", pp.get("edges"));
        } else {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) pp.get("nodes");
            List<Object> chain = new ArrayList<>(nodes.size());
            for (Map<String, Object> n : nodes) chain.add(n.getOrDefault("fqName", n.getOrDefault("name", "")));
            out.put("chain", chain);
        }
        return out;
    }

    @Tool(name = "cv_db_impact",
            description = "Database blast radius: methods that read from or write to a given table. If `project` is omitted, the default is used.")
    public Map<String, Object> dbImpact(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Table name.") String table,
            @ToolParam(description = "Optional column name to narrow the result.", required = false) String column) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, List<Map<String, Object>>> impact = store.dbImpact(p.projectId(), table, column);
        List<Map<String, Object>> readers = impact.getOrDefault("readers", List.of());
        List<Map<String, Object>> writers = impact.getOrDefault("writers", List.of());
        Map<String, Object> out = newResponse(p);
        out.put("table", table);
        out.put("column", column);
        out.put("readerCount", readers.size());
        out.put("writerCount", writers.size());
        out.put("readers", readers);
        out.put("writers", writers);
        return out;
    }

    @Tool(name = "cv_guard",
            description = "Quality gate snapshot: per-rule pass/fail and worst-offender breakdown. If `project` is omitted, the default is used.")
    public Map<String, Object> guard(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        out.putAll(store.guardSummary(p.projectId()));
        return out;
    }

    @Tool(name = "cv_diff_start",
            description = "Start an async git diff between two commits for a project. If `project` is omitted, the default is used.")
    public Map<String, Object> diffStart(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project,
            @ToolParam(description = "Base commit / ref (older).") String shaA,
            @ToolParam(description = "Target commit / ref (newer).") String shaB,
            @ToolParam(description = "Also diff CALLS edges (heavier query).", required = false) Boolean includeCalls,
            @ToolParam(description = "Keep snapshot data after diff (default false).", required = false) Boolean keep) {
        ProjectEntry p = projects.resolveOrDefault(project);
        boolean inc = includeCalls != null && includeCalls;
        boolean k = keep != null && keep;
        Map<String, Object> out = newResponse(p);
        out.putAll(CvectorDiffSubprocess.start(shaA, shaB, inc, k));
        return out;
    }

    @Tool(name = "cv_diff_status",
            description = "Poll the status of the most recent cv_diff_start.")
    public Map<String, Object> diffStatus() {
        return CvectorDiffSubprocess.status();
    }

    @Tool(name = "cv_wiki",
            description = "Structured documentation snapshot for a project. If `project` is omitted, the default is used.")
    public Map<String, Object> wiki(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project) {
        ProjectEntry p = projects.resolveOrDefault(project);
        String pid = p.projectId();
        Map<String, Object> out = newResponse(p);
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
            description = "Dependency vulnerability check via OSV.dev for every MavenDependency in a project. If `project` is omitted, the default is used.")
    public Map<String, Object> audit(
            @ToolParam(description = "Project name, UUID, or rootPath. Omit to use the default.", required = false) String project) {
        ProjectEntry p = projects.resolveOrDefault(project);
        Map<String, Object> out = newResponse(p);
        List<Map<String, Object>> deps = store.mavenDependencies(p.projectId());
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

    // ===========================================================================================
    //  Internals
    // ===========================================================================================

    /**
     * Canonical project block embedded in every response so the caller (human or AI) can
     * verify which project the result is from. Crucial when the {@code project} arg was
     * elided and the tool resolved to the workspace default.
     */
    private static Map<String, Object> projectContext(ProjectEntry p) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("projectId", p.projectId());
        ctx.put("name", p.name());
        ctx.put("rootPath", p.rootPath());
        ctx.put("isolated", p.isolatedOrDefault());
        return ctx;
    }

    /** Fresh mutable response map seeded with the project context block. */
    private static Map<String, Object> newResponse(ProjectEntry p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectContext(p));
        return out;
    }

    private Map<String, Object> aggregateAcrossProjects(java.util.function.Function<ProjectEntry, Map<String, Object>> compute) {
        List<Map<String, Object>> perProject = new ArrayList<>();
        for (ProjectEntry p : projects.loadConfig().projects().values()) {
            try {
                perProject.add(compute.apply(p));
            } catch (RuntimeException ignored) { /* unreachable / corrupt — skip */ }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("crossProject", true);
        out.put("perProject", perProject);
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
