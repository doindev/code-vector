package io.doindev.cvector.dashboard;

import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rest.ActiveProject;
import io.doindev.cvector.watcher.CvectorWatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activates live file-system watchers for every enabled {@link DashboardStore.Monitor}.
 * Conceptual mirror of {@link ScheduleRunner} but for paths instead of cron triggers.
 *
 * <p>Each monitor's path becomes its own {@link ProjectContext} root, so a {@code CvectorWatcher}
 * indexes file changes relative to that directory. The {@code projectId} is taken from the
 * dashboard's {@link ActiveProject} -- file events flow into the active project's graph so
 * the existing query / explain / impact endpoints see them. If the user wants per-monitor
 * projects, they switch the active project and re-enable the monitors.
 *
 * <p>Only constructed when an {@code ActiveProject} bean exists (i.e. when running in web
 * mode), so one-shot CLI invocations don't accidentally start watchers.
 */
@Component
@ConditionalOnBean(ActiveProject.class)
public class MonitorRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitorRunner.class);
    private static final long DEBOUNCE_MS = 250L;

    private final DashboardStore store;
    private final ActiveProject project;
    private final List<Parser> parsers;
    private final GraphStore graphStore;
    private final Map<String, CvectorWatcher> active = new ConcurrentHashMap<>();

    public MonitorRunner(DashboardStore store,
                         ActiveProject project,
                         List<Parser> parsers,
                         GraphStore restGraphStore) {
        this.store = store;
        this.project = project;
        this.parsers = parsers;
        this.graphStore = restGraphStore;
    }

    @PostConstruct
    void onStart() {
        for (DashboardStore.Monitor m : store.monitors()) activate(m);
        log.info("monitor runner initialised with {} active watcher(s)", active.size());
    }

    @PreDestroy
    void onStop() {
        for (CvectorWatcher w : active.values()) {
            try { w.close(); } catch (Exception ignore) { /* shutdown best-effort */ }
        }
        active.clear();
    }

    /**
     * Idempotent: stops any prior watcher for this id, then starts a fresh one when the
     * monitor is enabled. No-op for disabled monitors. Path-validation lives here so users
     * see "started" log lines only for paths that actually exist.
     */
    public synchronized void activate(DashboardStore.Monitor m) {
        deactivate(m.id());
        if (!m.enabled()) return;
        Path root = Paths.get(m.path());
        if (!Files.isDirectory(root)) {
            log.warn("monitor '{}' is not a directory; skipping (refresh after the path exists to retry)", m.path());
            return;
        }
        ProjectContext ctx = new ProjectContext(project.projectId(), project.name(), root);
        CvectorWatcher w = new CvectorWatcher(ctx, parsers, graphStore, DEBOUNCE_MS);
        try {
            w.start();
            active.put(m.id(), w);
            log.info("started watcher for {}", root);
        } catch (Exception e) {
            log.warn("failed to start watcher for {}: {}", root, e.getMessage());
            try { w.close(); } catch (Exception ignore) { }
        }
    }

    public synchronized void deactivate(String id) {
        CvectorWatcher w = active.remove(id);
        if (w == null) return;
        try { w.close(); } catch (Exception ignore) { /* best-effort */ }
    }

    /** Diagnostic snapshot for the dashboard. Exposed at {@code GET /api/dashboard/monitors/status}. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active.size());
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        for (Map.Entry<String, CvectorWatcher> e : active.entrySet()) {
            CvectorWatcher w = e.getValue();
            details.put(e.getKey(), Map.of(
                    "filesProcessed", w.totalFilesProcessed(),
                    "deletes", w.totalDeletes(),
                    "failures", w.transientFailures()
            ));
        }
        out.put("watchers", details);
        return out;
    }
}
