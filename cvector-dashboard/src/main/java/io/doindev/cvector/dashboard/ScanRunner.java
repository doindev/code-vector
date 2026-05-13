package io.doindev.cvector.dashboard;

import io.doindev.cvector.rest.GraphMutatedEvent;
import io.doindev.cvector.rest.ScanStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-demand scan launcher for the dashboard. The Overview view's "Run scan" button
 * POSTs here to fire a {@code cvector scan} or {@code cvector scan:incremental}
 * subprocess. Only one scan is permitted in flight at a time — Kuzu's single-writer
 * lock would reject a concurrent open anyway, so we short-circuit before forking
 * a doomed JVM.
 *
 * <p>Conceptually similar to {@link ScheduleRunner} but for ad-hoc triggers; both
 * spawn out-of-process to keep the dashboard JVM responsive.
 */
@Component
public class ScanRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    /** Actions that {@code cvector} understands as scan-style commands. */
    private static final Set<String> KNOWN_ACTIONS = Set.of("scan", "scan-incremental", "scan:incremental");

    private final AtomicReference<Process> current = new AtomicReference<>();
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<String> currentAction = new AtomicReference<>();
    private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>();
    private final AtomicReference<Integer> lastExitCode = new AtomicReference<>();

    /**
     * Optional because the REST module's event-publisher beans aren't always on the classpath
     * during unit tests of the dashboard module alone. Use {@link ObjectProvider#ifAvailable}
     * to no-op when missing.
     */
    private final ObjectProvider<ApplicationEventPublisher> events;

    public ScanRunner(ObjectProvider<ApplicationEventPublisher> events) {
        this.events = events;
    }

    /**
     * Attempt to start a scan. Returns a status map describing the result:
     *   {ok: true,  pid: N, startedAt: ...}   on a fresh spawn
     *   {ok: false, reason: "already-running"} if a prior scan is still in flight
     *   {ok: false, reason: "unknown-action"|"no-jar"}  on validation failures
     */
    public synchronized Map<String, Object> start(String action) {
        if (!KNOWN_ACTIONS.contains(action)) {
            return Map.of("ok", false, "reason", "unknown-action", "action", action);
        }
        Process existing = current.get();
        if (existing != null && existing.isAlive()) {
            return Map.of("ok", false, "reason", "already-running",
                    "pid", existing.pid(),
                    "action", currentAction.get() == null ? "" : currentAction.get());
        }
        String jar = locateCvectorJar();
        if (jar == null) {
            return Map.of("ok", false, "reason", "no-jar");
        }
        String normalized = "scan-incremental".equals(action) ? "scan:incremental" : action;
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar, normalized);
            pb.inheritIO();
            Process p = pb.start();
            current.set(p);
            startedAt.set(Instant.now());
            currentAction.set(normalized);
            log.info("dashboard-triggered scan started: pid={} action={}", p.pid(), normalized);
            final long pidForEvent = p.pid();
            final String actionForEvent = normalized;
            events.ifAvailable(pub -> pub.publishEvent(
                    ScanStatusChangedEvent.started(actionForEvent, pidForEvent)));
            // onExit: clear the in-flight pointer, record the final outcome for the UI.
            p.onExit().whenComplete((proc, err) -> {
                current.compareAndSet(proc, null);
                lastFinishedAt.set(Instant.now());
                try { lastExitCode.set(proc.exitValue()); } catch (IllegalThreadStateException ignore) { /* shouldn't reach here */ }
                startedAt.set(null);
                currentAction.set(null);
                log.info("dashboard-triggered scan finished: pid={} exit={}", proc.pid(), lastExitCode.get());
                // Two events: ScanStatusChangedEvent lets live SPA views update their
                // banners without a poll; GraphMutatedEvent invalidates the read cache
                // so the next /api/wiki etc. returns post-scan data.
                final long pidForExitEvent = proc.pid();
                final int exitCodeForEvent = lastExitCode.get() == null ? -1 : lastExitCode.get();
                // Use the captured `normalized` from the outer scope — currentAction is
                // null by the time we get here (we cleared it just above), so reading from
                // the atomic would always return "unknown".
                events.ifAvailable(pub -> {
                    pub.publishEvent(ScanStatusChangedEvent.finished(
                            normalized, pidForExitEvent, exitCodeForEvent));
                    pub.publishEvent(GraphMutatedEvent.fromScan());
                });
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pid", p.pid());
            out.put("startedAt", Instant.now().toString());
            out.put("action", normalized);
            return out;
        } catch (Exception e) {
            log.error("failed to start scan subprocess: {}", e.getMessage());
            return Map.of("ok", false, "reason", "subprocess-failed", "message", e.getMessage());
        }
    }

    /** Snapshot for the dashboard's live status indicator. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Process p = current.get();
        boolean running = p != null && p.isAlive();
        out.put("running", running);
        if (running) {
            out.put("pid", p.pid());
            Instant start = startedAt.get();
            if (start != null) {
                out.put("startedAt", start.toString());
                out.put("elapsedMillis", java.time.Duration.between(start, Instant.now()).toMillis());
            }
            String action = currentAction.get();
            if (action != null) out.put("action", action);
        }
        Instant last = lastFinishedAt.get();
        if (last != null) {
            out.put("lastFinishedAt", last.toString());
            Integer code = lastExitCode.get();
            if (code != null) out.put("lastExitCode", code);
        }
        return out;
    }

    /**
     * Best-effort lookup of the path to the cvector fat jar that's currently running.
     * Same mechanism used by {@link ScheduleRunner#locateCvectorJar}; lives here too to
     * avoid coupling the two runners through an extra utility class. Returns null when
     * not running from a packaged jar (IDE, tests).
     */
    private static String locateCvectorJar() {
        String jarCommand = System.getProperty("sun.java.command", "");
        if (jarCommand.isBlank()) return null;
        String token = jarCommand.split("\\s+", 2)[0];
        if (!token.toLowerCase().endsWith(".jar")) return null;
        File jar = new File(token);
        return jar.isFile() ? jar.getAbsolutePath() : null;
    }
}
