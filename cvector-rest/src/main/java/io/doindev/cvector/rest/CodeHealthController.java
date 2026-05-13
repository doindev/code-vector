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
 * Code-smell rollup (god files / god classes / long methods / dead code) backed by
 * {@link GraphStore#healthRollup}. Named {@code /api/code-health} rather than
 * {@code /api/health} so it doesn't collide with the Spring Boot health endpoint
 * exposed at the same path under {@link StatsController}.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class CodeHealthController {

    private final GraphStore store;
    private final ActiveProject project;
    private final GraphReadCache cache;

    public CodeHealthController(GraphStore restGraphStore, ActiveProject activeProject, GraphReadCache cache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.cache = cache;
    }

    @GetMapping("/code-health")
    public Map<String, Object> codeHealth() {
        return cache.memoize("code-health:" + project.projectId(), () -> {
            Map<String, List<Map<String, Object>>> rollup = store.healthRollup(project.projectId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", Map.of(
                    "projectId", project.projectId(),
                    "name", project.name()
            ));
            out.put("godFiles", rollup.getOrDefault("godFiles", List.of()));
            out.put("godClasses", rollup.getOrDefault("godClasses", List.of()));
            out.put("longMethods", rollup.getOrDefault("longMethods", List.of()));
            out.put("deadCode", rollup.getOrDefault("deadCode", List.of()));
            return out;
        });
    }
}
