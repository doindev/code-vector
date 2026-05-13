package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class StatsController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;
    private final CvectorConfigService configService;
    private final WorkspaceSwitcher switcher;

    public StatsController(GraphStore restGraphStore, ActiveProject activeProject,
                           JsonCache jsonCache, CvectorConfigService configService,
                           WorkspaceSwitcher switcher) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
        this.configService = configService;
        this.switcher = switcher;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = store.ping();
        out.put("status", ok ? "UP" : "DOWN");
        out.put("backend", store.backend());
        out.put("uri", store.displayUri());
        out.put("project", project.name());
        out.put("projectId", project.projectId());
        return out;
    }

    @GetMapping(value = "/stats", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] stats() {
        return jsonCache.memoize("stats:" + project.projectId(), () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", project.name());
            out.put("projectId", project.projectId());
            out.put("nodes", store.nodeCounts(project.projectId()));
            out.put("edges", store.edgeCounts(project.projectId()));
            return out;
        });
    }

    /**
     * Returns the workspace's full project list (from {@code .cvector/settings.json}) plus the
     * currently-active project. For embedded Kuzu, only the active project has an open graph
     * — node/edge counts are populated for the active one and left as {@code null} on the rest.
     * Future: per-project counts via a quick read against each project's Kuzu directory.
     */
    @GetMapping(value = "/projects", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] projects() {
        return jsonCache.memoize("projects:" + project.projectId(), this::buildProjects);
    }

    private Map<String, Object> buildProjects() {
        Map<String, Object> out = new LinkedHashMap<>();
        // Active project pulled from the bean — that's the one with a live GraphStore.
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("projectId", project.projectId());
        active.put("name", project.name());
        active.put("rootPath", project.rootPath());
        active.put("backend", store.backend());
        active.put("uri", store.displayUri());
        Map<String, Long> nodes = store.nodeCounts(project.projectId());
        Map<String, Long> edges = store.edgeCounts(project.projectId());
        active.put("nodes", nodes.values().stream().mapToLong(Long::longValue).sum());
        active.put("edges", edges.values().stream().mapToLong(Long::longValue).sum());
        out.put("active", active);

        // Full workspace list from settings.json. Read defensively — if the file moved or is
        // mid-edit we'd rather degrade to "just the active project" than 500.
        List<Map<String, Object>> all = readWorkspaceProjects();
        out.put("projects", all);
        out.put("count", all.size());
        return out;
    }

    /**
     * Atomically swap the active project. Writes settings.json, points the embedded backend
     * at the new project's data files, mutates the live {@link ActiveProject} bean, and
     * flushes both cache layers. On Neo4j the backend swap is a no-op — every query
     * filters by projectId, so updating the bean is sufficient.
     */
    @PostMapping("/projects/switch")
    public ResponseEntity<Map<String, Object>> switchActive(@RequestParam("key") String key) {
        Map<String, Object> result = switcher.switchTo(key);
        boolean ok = result.get("ok") instanceof Boolean b && b;
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    private List<Map<String, Object>> readWorkspaceProjects() {
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            Path root = configService.findConfigRoot(cwd);
            if (root == null) return List.of();
            CvectorConfig cfg = configService.load(root);
            List<Map<String, Object>> out = new ArrayList<>(cfg.projects().size());
            for (Map.Entry<String, CvectorConfig.ProjectEntry> e : cfg.projects().entrySet()) {
                CvectorConfig.ProjectEntry p = e.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", e.getKey());
                row.put("projectId", p.projectId());
                row.put("name", p.name());
                row.put("rootPath", p.rootPath());
                row.put("active", e.getKey().equals(cfg.activeProject()));
                out.add(row);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
