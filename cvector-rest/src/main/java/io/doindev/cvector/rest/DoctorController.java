package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated system-health checks for the dashboard's Doctor view — same intent as the
 * CLI {@code cvector doctor} but returned as structured JSON so the UI can render
 * pass/fail badges. Each check produces a {@code {name, status: ok|warn|fail, message}}
 * row; the response also surfaces JVM info, cache size, and active-project metadata for
 * troubleshooting handoff.
 *
 * <p>The endpoint is intentionally cheap — every check completes in microseconds (ping
 * does a single Cypher round-trip, the rest are local). Not cached because the user runs
 * Doctor to see the live state.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class DoctorController {

    private final GraphStore store;
    private final ActiveProject project;
    private final GraphReadCache cache;
    private final JsonCache jsonCache;
    /** Read straight from the Spring Environment so we see whatever's actually wired (properties or system). */
    private final boolean virtualThreadsEnabled;

    public DoctorController(GraphStore restGraphStore, ActiveProject activeProject, GraphReadCache cache,
                            JsonCache jsonCache,
                            @Value("${spring.threads.virtual.enabled:false}") boolean virtualThreadsEnabled) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.cache = cache;
        this.jsonCache = jsonCache;
        this.virtualThreadsEnabled = virtualThreadsEnabled;
    }

    @GetMapping("/doctor")
    public Map<String, Object> doctor() {
        Map<String, Object> out = new LinkedHashMap<>();
        // Empty-workspace tolerance: ActiveProject fields can be null when settings.json
        // has no projects yet. Map.of() rejects null values, so build a LinkedHashMap
        // explicitly (which accepts them) and emit a null `project` when there's nothing
        // to report. The rest of the doctor surface (jvm, cache, backend connectivity)
        // is still meaningful without a project.
        if (project.projectId() == null) {
            out.put("project", null);
        } else {
            Map<String, Object> pInfo = new LinkedHashMap<>();
            pInfo.put("projectId", project.projectId());
            pInfo.put("name", project.name());
            pInfo.put("rootPath", project.rootPath());
            out.put("project", pInfo);
        }
        out.put("backend", Map.of(
                "kind", store.backend(),
                "uri", store.displayUri()
        ));
        out.put("jvm", jvmInfo());
        out.put("cache", buildCacheSummary());
        out.put("checks", runChecks());
        return out;
    }

    /**
     * Roll up both cache layers' entries + hit/miss counters. Counters reset on every
     * {@link GraphMutatedEvent}-driven invalidation (after each scan), so the surface here
     * is "since the last scan" — which matches the time window operators usually care about.
     */
    private Map<String, Object> buildCacheSummary() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("entries", cache.size());
        obj.put("hits", cache.hits());
        obj.put("misses", cache.misses());
        obj.put("hitRate", hitRate(cache.hits(), cache.misses()));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("entries", jsonCache.size());
        json.put("hits", jsonCache.hits());
        json.put("misses", jsonCache.misses());
        json.put("hitRate", hitRate(jsonCache.hits(), jsonCache.misses()));

        Map<String, Object> all = new LinkedHashMap<>();
        all.put("object", obj);
        all.put("json", json);
        return all;
    }

    private static double hitRate(long hits, long misses) {
        long total = hits + misses;
        if (total == 0) return 0.0;
        // Two-decimal precision; the dashboard renders as a percentage so 0.81 = "81%".
        return Math.round(((double) hits / total) * 100.0) / 100.0;
    }

    private List<Map<String, Object>> runChecks() {
        List<Map<String, Object>> checks = new ArrayList<>();

        // Empty-workspace handling: ActiveProject may carry null fields when no project
        // is registered yet. Surface that as a top-level "no active project" check so
        // doctor still returns 200, then skip the per-project graph counts below.
        boolean hasProject = project.projectId() != null;
        if (hasProject) {
            checks.add(check("Active project", "ok",
                    "resolved to " + project.name() + " (" + project.projectId() + ")"));
        } else {
            checks.add(check("Active project", "warn",
                    "no project registered yet — run `cvector init` or call cv_add_project / cv_onboard_project"));
        }

        long pingStart = System.nanoTime();
        boolean pingOk;
        try { pingOk = store.ping(); } catch (Exception e) { pingOk = false; }
        long pingMs = (System.nanoTime() - pingStart) / 1_000_000;
        checks.add(check("Graph backend reachable",
                pingOk ? "ok" : "fail",
                pingOk
                        ? "ping completed in " + pingMs + " ms via " + store.backend()
                        : "ping failed against " + store.displayUri()));

        boolean schemaOk;
        try { schemaOk = store.schemaReady(); } catch (Exception e) { schemaOk = false; }
        checks.add(check("Schema bootstrapped",
                schemaOk ? "ok" : "fail",
                schemaOk ? "all expected labels/relationships present" : "schema bootstrap missing — run cvector scan"));

        if (hasProject) {
            long totalNodes = store.nodeCounts(project.projectId()).values().stream().mapToLong(Long::longValue).sum();
            long totalEdges = store.edgeCounts(project.projectId()).values().stream().mapToLong(Long::longValue).sum();
            boolean hasData = totalNodes > 0;
            checks.add(check("Graph has data",
                    hasData ? "ok" : "warn",
                    hasData
                            ? totalNodes + " node(s) / " + totalEdges + " edge(s)"
                            : "no nodes in graph — run a cvector scan to populate"));
        } else {
            checks.add(check("Graph has data", "skip",
                    "no active project — nothing to query yet"));
        }

        checks.add(check("Virtual threads",
                virtualThreadsEnabled ? "ok" : "warn",
                virtualThreadsEnabled
                        ? "spring.threads.virtual.enabled = true"
                        : "running on platform threads — set spring.threads.virtual.enabled=true"));

        // Heap pressure: warn at >80% live, fail at >95%. Compares used/max from the heap mbean.
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double heapPct = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0.0;
        String heapStatus = heapPct > 0.95 ? "fail" : heapPct > 0.80 ? "warn" : "ok";
        checks.add(check("Heap headroom", heapStatus,
                String.format("used %s / max %s (%d%%)",
                        bytes(heap.getUsed()), bytes(heap.getMax()), Math.round(heapPct * 100))));

        return checks;
    }

    private Map<String, Object> jvmInfo() {
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("javaVersion", System.getProperty("java.version"));
        jvm.put("vmName", System.getProperty("java.vm.name"));
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        jvm.put("heapUsedMb", heap.getUsed() / (1024 * 1024));
        jvm.put("heapMaxMb", heap.getMax() / (1024 * 1024));
        jvm.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        return jvm;
    }

    private static Map<String, Object> check(String name, String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("message", message);
        return m;
    }

    private static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return (b / (1024 * 1024)) + " MB";
        return (b / (1024L * 1024 * 1024)) + " GB";
    }
}
