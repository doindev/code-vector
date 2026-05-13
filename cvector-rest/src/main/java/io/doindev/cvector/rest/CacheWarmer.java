package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-populates the {@link JsonCache} with the heavy analytical endpoints' responses as soon
 * as Spring is ready, so the first user request after boot hits warm cache instead of paying
 * the ~300–900 ms cold compute. Fires on {@link ApplicationReadyEvent}; runs on a daemon
 * thread so the boot path isn't blocked.
 *
 * <p>Strategy: precompute the same object shapes the controllers do, then call
 * {@code jsonCache.memoize(key, () -> precomputed)} so the cache key matches what the
 * controller would have used. The next live request finds the entry already populated and
 * returns instantly. Failures are swallowed — if a warm-up call errors (e.g. graph mid-bootstrap),
 * the controller will just serve the live request normally.
 */
@Component
@ConditionalOnWebApplication
// Force eager construction: the workspace runs with spring.main.lazy-initialization=true,
// which would otherwise skip this bean (nothing injects it; the @EventListener can't fire on
// a bean that was never built). @Lazy(false) opts back in to eager init for just this class.
@Lazy(false)
public class CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public CacheWarmer(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
        // Fire-and-forget the warm pass from the constructor. The bean's dependencies are all
        // injected — GraphStore + JsonCache + ActiveProject are usable at this point. Trying
        // to defer via @EventListener(ApplicationReadyEvent.class) doesn't work under
        // {@code spring.main.lazy-initialization=true}: the event fires before this lazy bean
        // is constructed, so the listener registers too late and never runs. Doing it from
        // the constructor is robust and runs on a daemon thread anyway.
        Thread t = new Thread(this::warm, "cvector-cache-warmer");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Warmer-inserted entries get a much longer TTL than the default 30 s — the dashboard's
     * "first paint after boot" use case is exactly the scenario where someone opens the page
     * minutes (or hours) after the server started. With 30 s TTL the warmer payloads have
     * expired well before the user clicks, so the warm-up provided no benefit. 30 min keeps
     * the cache useful across an entire workday; {@link GraphMutatedEvent} still flushes
     * promptly when a scan lands.
     */
    private static final Duration WARM_TTL = Duration.ofMinutes(30);

    private void warm() {
        Instant start = Instant.now();
        // Endpoints whose controllers compute against the live store but produce a stable
        // result we can mirror cheaply. {@code /api/wiki} has a complex multi-section shape;
        // duplicating its builder here would risk drift, so we leave wiki to warm on first
        // user hit. The other endpoints are simple enough to reproduce inline.
        warmEntry("stats:" + project.projectId(), this::buildStats);
        warmEntry("graph-schema:" + project.projectId(), this::buildSchema);
        warmEntry("onboard:" + project.projectId(), this::buildOnboard);
        warmEntry("service-links:" + project.projectId(), this::buildServiceLinks);
        warmEntry("code-health:" + project.projectId(), this::buildCodeHealth);
        warmEntry("guard:" + project.projectId(), this::buildGuard);
        long ms = Duration.between(start, Instant.now()).toMillis();
        log.info("cache warmer: pre-populated {} entries in {} ms (TTL {} min)",
                jsonCache.size(), ms, WARM_TTL.toMinutes());
    }

    /** Best-effort single-entry warm. Swallow failures so a transient graph state doesn't crash startup. */
    private void warmEntry(String key, java.util.function.Supplier<Map<String, Object>> compute) {
        try {
            jsonCache.memoize(key, WARM_TTL, compute);
        } catch (RuntimeException e) {
            log.debug("cache warmer: skipping {} ({})", key, e.getMessage());
        }
    }

    // The build* methods below mirror what the corresponding controllers do internally.
    // Keeping them inline (rather than refactoring controllers to expose them) avoids
    // dragging shared builders out of bounded scope; the duplication is small.

    private Map<String, Object> buildStats() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", pid);
        out.put("nodes", store.nodeCounts(pid));
        out.put("edges", store.edgeCounts(pid));
        return out;
    }

    private Map<String, Object> buildSchema() {
        String pid = project.projectId();
        Map<String, Long> nodes = store.nodeCounts(pid);
        Map<String, Long> edges = store.edgeCounts(pid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", pid);
        out.put("labels", toCountedRows(nodes));
        out.put("relTypes", toCountedRows(edges));
        out.put("totals", Map.of(
                "labels", nodes.size(),
                "relTypes", edges.size(),
                "nodes", nodes.values().stream().mapToLong(Long::longValue).sum(),
                "edges", edges.values().stream().mapToLong(Long::longValue).sum()
        ));
        out.put("connectivity", store.schemaConnectivity(pid));
        return out;
    }

    private Map<String, Object> buildOnboard() {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", pid, "name", project.name(), "rootPath", project.rootPath()));
        out.put("totals", Map.of(
                "nodes", store.nodeCounts(pid).values().stream().mapToLong(Long::longValue).sum(),
                "edges", store.edgeCounts(pid).values().stream().mapToLong(Long::longValue).sum()
        ));
        Map<String, List<Map<String, Object>>> summary = store.onboardSummary(pid);
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

    private Map<String, Object> buildServiceLinks() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));
        Map<String, List<Map<String, Object>>> links = store.serviceLinks(project.projectId());
        out.put("outgoingHttp", links.getOrDefault("outgoingHttp", List.of()));
        out.put("outgoingMessaging", links.getOrDefault("outgoingMessaging", List.of()));
        out.put("incomingConsumers", links.getOrDefault("incomingConsumers", List.of()));
        out.put("restEndpoints", links.getOrDefault("restEndpoints", List.of()));
        out.put("tablesTouched", links.getOrDefault("tablesTouched", List.of()));
        return out;
    }

    private Map<String, Object> buildCodeHealth() {
        Map<String, List<Map<String, Object>>> rollup = store.healthRollup(project.projectId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));
        out.put("godFiles", rollup.getOrDefault("godFiles", List.of()));
        out.put("godClasses", rollup.getOrDefault("godClasses", List.of()));
        out.put("longMethods", rollup.getOrDefault("longMethods", List.of()));
        out.put("deadCode", rollup.getOrDefault("deadCode", List.of()));
        return out;
    }

    private Map<String, Object> buildGuard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));
        out.putAll(store.guardSummary(project.projectId()));
        return out;
    }

    private static List<Map<String, Object>> toCountedRows(Map<String, Long> raw) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(raw.size());
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("count", e.getValue());
            rows.add(row);
        }
        rows.sort(java.util.Comparator
                .comparingLong((Map<String, Object> r) -> -((Number) r.get("count")).longValue())
                .thenComparing(r -> (String) r.get("name")));
        return rows;
    }
}
