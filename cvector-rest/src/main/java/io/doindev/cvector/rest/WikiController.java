package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documentation-style snapshot of the active project, modelled after the CLI
 * {@code cvector wiki} command but returned as a single structured payload so the
 * dashboard can render it without writing files to disk. Every section uses the same
 * {@code {id, title, kind:"table", columns, rows}} shape so the Angular Wiki view
 * needs exactly one renderer.
 *
 * <p>Deliberately avoids the per-class {@code contains} fan-out the CLI wiki does --
 * {@link GraphStore#onboardSummary} already exposes top-N classes with method counts,
 * which is what the dashboard needs (full class listings would push the payload past
 * useful sizes for projects with thousands of classes).
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class WikiController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public WikiController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    /** Pre-serialised JSON bytes; wiki is the heaviest read endpoint (~60 KB raw on cvector itself). */
    @GetMapping(value = "/wiki", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] wiki() {
        return jsonCache.memoize("wiki:" + project.projectId(), this::buildWiki);
    }

    private Map<String, Object> buildWiki() {
        String pid = project.projectId();
        Map<String, Long> nodes = store.nodeCounts(pid);
        Map<String, Long> edges = store.edgeCounts(pid);
        Map<String, List<Map<String, Object>>> onboard = store.onboardSummary(pid);
        Map<String, List<Map<String, Object>>> infra = store.infrastructureSummary(pid);

        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(graphNodesSection(nodes));
        sections.add(graphEdgesSection(edges));
        sections.add(filesSection(pid));
        sections.add(topClassesSection(onboard));
        sections.add(restEndpointsSection(onboard));
        sections.add(databaseTablesSection(onboard));
        sections.add(configKeysSection(infra));
        sections.add(envVarsSection(infra));
        sections.add(mavenDependenciesSection(pid));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", pid,
                "name", project.name(),
                "rootPath", project.rootPath(),
                "backend", store.backend()
        ));
        out.put("generatedAt", ZonedDateTime.now().toString());
        out.put("totals", Map.of(
                "nodes", nodes.values().stream().mapToLong(Long::longValue).sum(),
                "edges", edges.values().stream().mapToLong(Long::longValue).sum()
        ));
        out.put("sections", sections);
        return out;
    }

    private Map<String, Object> graphNodesSection(Map<String, Long> nodes) {
        return tableSection("graph-nodes", "Graph nodes",
                List.of("Label", "Count"),
                nodes.entrySet().stream()
                        .map(e -> row("Label", e.getKey(), "Count", e.getValue()))
                        .toList());
    }

    private Map<String, Object> graphEdgesSection(Map<String, Long> edges) {
        return tableSection("graph-edges", "Graph edges",
                List.of("Type", "Count"),
                edges.entrySet().stream()
                        .map(e -> row("Type", e.getKey(), "Count", e.getValue()))
                        .toList());
    }

    private Map<String, Object> filesSection(String pid) {
        return tableSection("files", "Files",
                List.of("Path", "Language", "Methods", "Last ingested"),
                store.fileInventory(pid).stream()
                        .map(r -> row(
                                "Path", r.get("path"),
                                "Language", r.get("language"),
                                "Methods", r.getOrDefault("methodCount", 0),
                                "Last ingested", r.get("lastIngestedAt")
                        ))
                        .toList());
    }

    private Map<String, Object> topClassesSection(Map<String, List<Map<String, Object>>> onboard) {
        return tableSection("classes", "Top classes",
                List.of("Class", "Methods"),
                onboard.getOrDefault("topClasses", List.of()).stream()
                        .map(r -> row(
                                "Class", r.get("fqName"),
                                "Methods", r.getOrDefault("methodCount", 0)
                        ))
                        .toList());
    }

    private Map<String, Object> restEndpointsSection(Map<String, List<Map<String, Object>>> onboard) {
        return tableSection("endpoints", "REST endpoints",
                List.of("Method", "Path", "Framework"),
                onboard.getOrDefault("restEndpoints", List.of()).stream()
                        .map(r -> row(
                                "Method", r.get("httpMethod"),
                                "Path", r.get("path"),
                                "Framework", r.get("framework")
                        ))
                        .toList());
    }

    private Map<String, Object> databaseTablesSection(Map<String, List<Map<String, Object>>> onboard) {
        return tableSection("tables", "Database tables",
                List.of("Table", "Columns"),
                onboard.getOrDefault("tables", List.of()).stream()
                        .map(r -> row(
                                "Table", r.get("table"),
                                "Columns", r.getOrDefault("columns", 0)
                        ))
                        .toList());
    }

    private Map<String, Object> configKeysSection(Map<String, List<Map<String, Object>>> infra) {
        return tableSection("config", "Config keys",
                List.of("Key"),
                infra.getOrDefault("configKeys", List.of()).stream()
                        .map(r -> row("Key", r.get("fqName")))
                        .toList());
    }

    private Map<String, Object> envVarsSection(Map<String, List<Map<String, Object>>> infra) {
        return tableSection("env", "Environment variables",
                List.of("Name", "Value"),
                infra.getOrDefault("envVars", List.of()).stream()
                        .map(r -> row(
                                "Name", r.get("fqName"),
                                "Value", r.get("value")
                        ))
                        .toList());
    }

    private Map<String, Object> mavenDependenciesSection(String pid) {
        return tableSection("dependencies", "Maven dependencies",
                List.of("Group", "Artifact", "Version", "Scope"),
                store.mavenDependencies(pid).stream()
                        .map(r -> row(
                                "Group", r.get("groupId"),
                                "Artifact", r.get("artifactId"),
                                "Version", r.get("version"),
                                "Scope", r.get("scope")
                        ))
                        .toList());
    }

    private static Map<String, Object> tableSection(String id, String title,
                                                    List<String> columns,
                                                    List<Map<String, Object>> rows) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("title", title);
        s.put("kind", "table");
        s.put("columns", columns);
        s.put("rows", rows);
        s.put("count", rows.size());
        return s;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
