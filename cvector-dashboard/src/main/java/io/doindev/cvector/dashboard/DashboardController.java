package io.doindev.cvector.dashboard;

import io.doindev.cvector.rest.ActiveProject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the cvector dashboard's persistent state. All endpoints live under
 * {@code /api/dashboard/}; payloads are plain JSON shaped after {@link DashboardStore}'s
 * records. Available only when the cvector-dashboard module is on the classpath (i.e.
 * the {@code dashboard-ui} Maven profile is active).
 */
@RestController
@RequestMapping("/api/dashboard")
@ConditionalOnBean(ActiveProject.class)
public class DashboardController {

    private final DashboardStore store;
    private final ScheduleRunner scheduler;
    private final MonitorRunner monitors;
    private final ScanRunner scans;
    private final DiffRunner diffs;
    private final RestartRunner restarts;
    private final ActiveProject project;

    public DashboardController(DashboardStore store, ScheduleRunner scheduler,
                               MonitorRunner monitors, ScanRunner scans, DiffRunner diffs,
                               RestartRunner restarts, ActiveProject project) {
        this.store = store;
        this.scheduler = scheduler;
        this.monitors = monitors;
        this.scans = scans;
        this.diffs = diffs;
        this.restarts = restarts;
        this.project = project;
    }

    // Monitors -----------------------------------------------------------------------

    @GetMapping("/monitors")
    public List<DashboardStore.Monitor> listMonitors() {
        return store.monitors();
    }

    @PostMapping("/monitors")
    public ResponseEntity<?> addMonitor(@RequestBody Map<String, String> body) {
        String raw = body == null ? "" : body.getOrDefault("path", "").trim();
        if (raw.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "path is required"));
        }
        Path proposed;
        Path root;
        try {
            proposed = Paths.get(raw).toRealPath();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "path does not exist: " + raw));
        }
        try {
            root = Paths.get(project.rootPath()).toRealPath();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "active project root is not accessible: " + project.rootPath()));
        }
        // Monitors must live inside the active project's tree — otherwise file events would
        // be attributed to the wrong projectId in the graph, contaminating queries. See
        // MonitorRunner: events use ActiveProject.projectId() regardless of monitor path.
        if (!proposed.equals(root) && !proposed.startsWith(root)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Monitor path must be inside the active project root: " + root,
                    "projectRoot", root.toString(),
                    "proposed", proposed.toString()));
        }
        DashboardStore.Monitor m = store.addMonitor(proposed.toString());
        // Async — DirectoryWatcher.build() walks the tree to register per-dir watches
        // and can take seconds on large repos. The UI polls /monitors/status to learn
        // when the watcher actually comes up.
        monitors.activateAsync(m);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/monitors/{id}/toggle")
    public ResponseEntity<DashboardStore.Monitor> toggleMonitor(@PathVariable String id) {
        DashboardStore.Monitor m = store.toggleMonitor(id);
        if (m == null) return ResponseEntity.notFound().build();
        // activate() is idempotent and cancels any prior watcher; toggling off-then-on
        // restarts the watcher in lockstep with persistence. Async for the same reason
        // as add — full DirectoryWatcher rebuild is slow.
        monitors.activateAsync(m);
        return ResponseEntity.ok(m);
    }

    @DeleteMapping("/monitors/{id}")
    public ResponseEntity<Void> removeMonitor(@PathVariable String id) {
        boolean removed = store.removeMonitor(id);
        if (removed) monitors.deactivateAsync(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/monitors/status")
    public Map<String, Object> monitorsStatus() {
        return monitors.status();
    }

    @GetMapping("/schedules/status")
    public Map<String, Object> schedulesStatus() {
        return scheduler.status();
    }

    // Ad-hoc scans --------------------------------------------------------------------

    @PostMapping("/scans")
    public ResponseEntity<Map<String, Object>> runScan(@RequestBody(required = false) Map<String, String> body) {
        String action = body == null ? "scan-incremental" : body.getOrDefault("action", "scan-incremental");
        Map<String, Object> result = scans.start(action);
        boolean ok = result.get("ok") instanceof Boolean b && b;
        // 409 Conflict communicates "still running" clearly; 400 for malformed input.
        if (!ok) {
            Object reason = result.get("reason");
            if ("already-running".equals(reason)) return ResponseEntity.status(409).body(result);
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/scans/status")
    public Map<String, Object> scansStatus() {
        return scans.status();
    }

    // Diff --------------------------------------------------------------------------

    @PostMapping("/diff")
    public ResponseEntity<Map<String, Object>> runDiff(@RequestBody Map<String, Object> body) {
        if (body == null) body = Map.of();
        String shaA = String.valueOf(body.getOrDefault("shaA", "")).trim();
        String shaB = String.valueOf(body.getOrDefault("shaB", "")).trim();
        boolean includeCalls = body.get("includeCalls") instanceof Boolean b && b;
        boolean keep = body.get("keep") instanceof Boolean k && k;
        if (shaA.isEmpty() || shaB.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "missing-shas"));
        }
        Map<String, Object> result = diffs.start(shaA, shaB, includeCalls, keep);
        boolean ok = result.get("ok") instanceof Boolean b && b;
        if (!ok) {
            Object reason = result.get("reason");
            if ("already-running".equals(reason)) return ResponseEntity.status(409).body(result);
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/diff/status")
    public Map<String, Object> diffStatus() {
        return diffs.status();
    }

    // Restart ----------------------------------------------------------------------

    /**
     * Spawn a detached replacement JVM running {@code cvector dashboard} and gracefully shut
     * the current one down ~700 ms later. The UI polls {@code /api/health} to know when the
     * new process is reachable and reloads. Returns 202 Accepted on success; 409 Conflict
     * if a restart is already in flight; 400 if no jar can be located (running from IDE).
     */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, Object>> restart() {
        Map<String, Object> result = restarts.restart();
        boolean ok = result.get("ok") instanceof Boolean b && b;
        if (!ok) {
            Object reason = result.get("reason");
            if ("already-restarting".equals(reason)) return ResponseEntity.status(409).body(result);
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    // Schedules ----------------------------------------------------------------------

    @GetMapping("/schedules")
    public List<DashboardStore.Schedule> listSchedules() {
        return store.schedules();
    }

    @PostMapping("/schedules")
    public DashboardStore.Schedule addSchedule(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        String cron = body.getOrDefault("cron", "").trim();
        String action = body.getOrDefault("action", "scan-incremental").trim();
        if (name.isEmpty() || cron.isEmpty()) {
            throw new IllegalArgumentException("name and cron are required");
        }
        DashboardStore.Schedule s = store.addSchedule(name, cron, action);
        scheduler.activate(s);
        return s;
    }

    @PostMapping("/schedules/{id}/toggle")
    public ResponseEntity<DashboardStore.Schedule> toggleSchedule(@PathVariable String id) {
        DashboardStore.Schedule s = store.toggleSchedule(id);
        if (s == null) return ResponseEntity.notFound().build();
        // Re-register: activate() is idempotent and cancels any prior trigger, so toggling
        // a schedule off (or on) updates the live scheduler in lockstep with persistence.
        scheduler.activate(s);
        return ResponseEntity.ok(s);
    }

    @PostMapping("/schedules/{id}/run")
    public ResponseEntity<Map<String, Object>> runScheduleNow(@PathVariable String id) {
        boolean ran = scheduler.runNow(id);
        if (!ran) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("triggered", true, "id", id));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<Void> removeSchedule(@PathVariable String id) {
        boolean removed = store.removeSchedule(id);
        if (removed) scheduler.deactivate(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // Query history --------------------------------------------------------------------

    @GetMapping("/queries")
    public List<DashboardStore.QueryRecord> listQueries() {
        return store.queries();
    }

    @PostMapping("/queries")
    public DashboardStore.QueryRecord addQuery(@RequestBody Map<String, Object> body) {
        String cypher = String.valueOf(body.getOrDefault("cypher", "")).trim();
        if (cypher.isEmpty()) {
            throw new IllegalArgumentException("cypher is required");
        }
        Object okObj = body.get("ok");
        boolean ok = !(okObj instanceof Boolean) || (boolean) okObj; // default true
        if (!ok) {
            String msg = String.valueOf(body.getOrDefault("errorMessage", "")).trim();
            return store.addQuery(DashboardStore.QueryRecord.ofError(cypher, msg.isEmpty() ? "query failed" : msg));
        }
        Object rcObj = body.get("rowCount");
        int rowCount = rcObj instanceof Number ? ((Number) rcObj).intValue() : 0;
        return store.addQuery(DashboardStore.QueryRecord.ofSuccess(cypher, rowCount));
    }

    @DeleteMapping("/queries/{id}")
    public ResponseEntity<Void> removeQuery(@PathVariable String id) {
        return store.removeQuery(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/queries")
    public ResponseEntity<Void> clearQueries() {
        store.clearQueries();
        return ResponseEntity.noContent().build();
    }

    // Settings -----------------------------------------------------------------------

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return store.settings();
    }

    @PutMapping("/settings")
    public Map<String, Object> putSettings(@RequestBody Map<String, Object> body) {
        store.putSettings(body);
        return store.settings();
    }
}
