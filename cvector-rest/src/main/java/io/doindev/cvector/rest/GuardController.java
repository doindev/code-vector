package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quality-gate snapshot for the dashboard's Guard view. Delegates to
 * {@link GraphStore#guardSummary} which already encodes the per-rule breakdown the CLI's
 * {@code cvector guard} command surfaces. Add project metadata so the dashboard can
 * render the project name alongside the pass/fail banner without a second call.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class GuardController {

    private final GraphStore store;
    private final ActiveProject project;

    public GuardController(GraphStore restGraphStore, ActiveProject activeProject) {
        this.store = restGraphStore;
        this.project = activeProject;
    }

    @GetMapping("/guard")
    public Map<String, Object> guard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", project.projectId(),
                "name", project.name()
        ));
        Map<String, Object> summary = store.guardSummary(project.projectId());
        out.putAll(summary);
        return out;
    }
}
