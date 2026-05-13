package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-briefing endpoint -- the REST surface behind cvector's CLI {@code onboard} command.
 * Composes the full architecture summary the dashboard's Overview view renders: language
 * distribution, top classes by method count, REST endpoints, table inventory, config keys,
 * env vars, and call-graph hubs. Backed by {@link GraphStore#onboardSummary} so the
 * heavy lifting (multiple Cypher queries) stays in the backend layer that already knows
 * how to optimise per-store.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class OnboardController {

    private final GraphStore store;
    private final ActiveProject project;
    private final GraphReadCache cache;

    public OnboardController(GraphStore restGraphStore, ActiveProject activeProject, GraphReadCache cache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.cache = cache;
    }

    @GetMapping("/onboard")
    public Map<String, Object> onboard() {
        return cache.memoize("onboard:" + project.projectId(), this::buildOnboard);
    }

    private Map<String, Object> buildOnboard() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", pid,
                "name", project.name(),
                "rootPath", project.rootPath()
        ));
        out.put("totals", Map.of(
                "nodes", store.nodeCounts(pid).values().stream().mapToLong(Long::longValue).sum(),
                "edges", store.edgeCounts(pid).values().stream().mapToLong(Long::longValue).sum()
        ));
        Map<String, List<Map<String, Object>>> summary = store.onboardSummary(pid);
        // Surface the keys the dashboard knows how to render. Anything else is passed through
        // for forward-compat -- a backend that adds new summary categories doesn't need a
        // controller change.
        out.put("languages", summary.getOrDefault("languages", List.of()));
        out.put("topClasses", summary.getOrDefault("topClasses", List.of()));
        out.put("restEndpoints", summary.getOrDefault("restEndpoints", List.of()));
        out.put("tables", summary.getOrDefault("tables", List.of()));
        out.put("configKeys", summary.getOrDefault("configKeys", List.of()));
        out.put("envVars", summary.getOrDefault("envVars", List.of()));
        out.put("callGraphHubs", summary.getOrDefault("callGraphHubs", List.of()));
        out.put("mavenDependencies", store.mavenDependencies(pid));
        return out;
    }
}
