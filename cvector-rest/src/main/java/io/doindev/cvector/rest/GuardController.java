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
    private final JsonCache jsonCache;

    public GuardController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/guard", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] guard() {
        // guardSummary fans out across multiple rule queries (god files, dead code,
        // call-graph density, etc.). Cache the pre-serialised snapshot so the Guard view's
        // polling cycle hits byte[] only — single Kuzu round trip + zero Jackson on warm.
        return jsonCache.memoize("guard:" + project.projectId(), () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", Map.of(
                    "projectId", project.projectId(),
                    "name", project.name()
            ));
            Map<String, Object> summary = store.guardSummary(project.projectId());
            out.putAll(summary);
            return out;
        });
    }
}
