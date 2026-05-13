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
 * Cross-service edge surface — what this project calls out to, what it receives, and which
 * tables it touches. Backed by {@link GraphStore#serviceLinks} so the dashboard can show
 * the same view the CLI's {@code cvector service-links} surfaces. Returning the categories
 * untouched (no totals, no rollup) keeps the controller dumb and lets the frontend decide
 * how to present each one.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class ServiceLinksController {

    private final GraphStore store;
    private final ActiveProject project;
    private final GraphReadCache cache;

    public ServiceLinksController(GraphStore restGraphStore, ActiveProject activeProject, GraphReadCache cache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.cache = cache;
    }

    @GetMapping("/service-links")
    public Map<String, Object> serviceLinks() {
        return cache.memoize("service-links:" + project.projectId(), this::buildServiceLinks);
    }

    private Map<String, Object> buildServiceLinks() {
        Map<String, List<Map<String, Object>>> links = store.serviceLinks(project.projectId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", project.projectId(),
                "name", project.name()
        ));
        out.put("outgoingHttp", links.getOrDefault("outgoingHttp", List.of()));
        out.put("outgoingMessaging", links.getOrDefault("outgoingMessaging", List.of()));
        out.put("incomingConsumers", links.getOrDefault("incomingConsumers", List.of()));
        out.put("restEndpoints", links.getOrDefault("restEndpoints", List.of()));
        out.put("tablesTouched", links.getOrDefault("tablesTouched", List.of()));
        return out;
    }
}
