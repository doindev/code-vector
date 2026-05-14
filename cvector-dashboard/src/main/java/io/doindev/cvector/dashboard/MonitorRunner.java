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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /**
     * Single-thread executor so monitor (de)activations don't block REST request threads.
     * Building a {@code DirectoryWatcher} over a large source tree walks the whole tree to
     * register per-directory watches and can take several seconds; doing that inline made
     * POST /api/dashboard/monitors look hung. Single-threaded preserves ordering so a
     * deactivate immediately following an activate runs after, never concurrently.
     */
    private final ExecutorService activator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "monitor-activator");
        t.setDaemon(true);
        return t;
    });

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
        activator.shutdownNow();
        try { activator.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
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
        // Defensive: file events from this watcher will be attributed to ActiveProject's
        // projectId. If the monitored path is outside the active project root the graph
        // would be contaminated with unrelated nodes, so we silently skip — the controller
        // is the gatekeeper for new monitors, this catches stored-then-project-switched cases.
        Path activeRoot;
        try {
            activeRoot = Paths.get(project.rootPath()).toRealPath();
            Path candidate = root.toRealPath();
            if (!candidate.equals(activeRoot) && !candidate.startsWith(activeRoot)) {
                log.warn("monitor '{}' is outside the active project root ({}); skipping", root, activeRoot);
                return;
            }
        } catch (java.io.IOException e) {
            log.warn("monitor '{}' path-resolution failed; skipping: {}", root, e.getMessage());
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

    /**
     * Fire-and-forget variant of {@link #activate(DashboardStore.Monitor)} for REST callers
     * that don't want to wait the full DirectoryWatcher setup time. The watcher appears in
     * {@link #status()} once it's actually running; the dashboard UI polls /status to learn
     * when that happens.
     */
    public void activateAsync(DashboardStore.Monitor m) {
        activator.submit(() -> activate(m));
    }

    /** Async counterpart to {@link #deactivate(String)} for symmetry with {@link #activateAsync}. */
    public void deactivateAsync(String id) {
        activator.submit(() -> deactivate(id));
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
