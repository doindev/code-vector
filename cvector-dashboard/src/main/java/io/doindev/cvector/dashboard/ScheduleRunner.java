package io.doindev.cvector.dashboard;

import io.doindev.cvector.rest.GraphMutatedEvent;
import io.doindev.cvector.rest.ScanStatusChangedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activates cron triggers for every enabled {@link DashboardStore.Schedule}. On startup
 * we reconcile against the persisted schedule list; afterwards the dashboard controller
 * notifies us when individual schedules are added, toggled, or removed.
 *
 * <p>Fired schedules currently spawn an out-of-process {@code java -jar cvector.jar <action>}
 * so the scheduler thread is never tied up by a multi-minute scan and the live dashboard
 * keeps responding. Output streams are inherited from the parent JVM so cron-driven scan
 * progress lands in the same console as the dashboard logs.
 *
 * <p>Only active when running in web mode (i.e. when the dashboard module's Spring
 * configuration is loaded). For one-shot CLI invocations the scheduler bean isn't created.
 */
@Component
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    /** Actions we know how to dispatch. Anything else is logged and skipped. */
    private static final Set<String> KNOWN_ACTIONS = Set.of("scan", "scan-incremental", "scan:incremental", "diff");

    private final DashboardStore store;
    private final TaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> active = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastFireStartedAt = new AtomicReference<>(null);
    /**
     * Live subprocess per schedule id. Used to skip the next cron fire if the previous
     * one is still running -- a multi-minute scan and a 1-minute cron would otherwise pile
     * up a queue of stalled subprocesses, all of which would fail on the embedded Kuzu
     * single-writer lock anyway.
     */
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    private final ObjectProvider<ApplicationEventPublisher> events;

    public ScheduleRunner(DashboardStore store, TaskScheduler scheduler,
                          ObjectProvider<ApplicationEventPublisher> events) {
        this.store = store;
        this.scheduler = scheduler;
        this.events = events;
    }

    @PostConstruct
    void onStart() {
        for (DashboardStore.Schedule s : store.schedules()) activate(s);
        log.info("scheduler initialised with {} active trigger(s)", active.size());
    }

    @PreDestroy
    void onStop() {
        active.values().forEach(f -> f.cancel(false));
        active.clear();
    }

    /** Idempotent: registers a cron trigger for the schedule, cancelling any prior one. */
    public synchronized void activate(DashboardStore.Schedule s) {
        deactivate(s.id());
        if (!s.enabled()) return;
        try {
            CronTrigger trigger = new CronTrigger(s.cron());
            ScheduledFuture<?> f = scheduler.schedule(() -> fire(s), trigger);
            if (f != null) active.put(s.id(), f);
            log.info("registered schedule '{}' (cron={}, action={})", s.name(), s.cron(), s.action());
        } catch (IllegalArgumentException e) {
            log.warn("invalid cron '{}' for schedule '{}': {}", s.cron(), s.name(), e.getMessage());
        }
    }

    /** Cancels the trigger associated with {@code id}, if any. */
    public synchronized void deactivate(String id) {
        ScheduledFuture<?> f = active.remove(id);
        if (f != null) f.cancel(false);
    }

    /** Manually fire a schedule (exposed via REST for "Run now" buttons in the UI). Returns false if not found. */
    public boolean runNow(String id) {
        for (DashboardStore.Schedule s : store.schedules()) {
            if (s.id().equals(id)) {
                fire(s);
                return true;
            }
        }
        return false;
    }

    private void fire(DashboardStore.Schedule s) {
        if (!KNOWN_ACTIONS.contains(s.action())) {
            log.warn("schedule '{}' has unknown action '{}', skipping", s.name(), s.action());
            return;
        }
        // Skip-if-still-running: if a prior fire's subprocess hasn't exited yet, log + skip.
        // Avoids piling up subprocesses that would all fail on the Kuzu single-writer lock.
        Process prior = running.get(s.id());
        if (prior != null && prior.isAlive()) {
            log.info("schedule '{}' still running from prior fire (pid={}), skipping", s.name(), prior.pid());
            return;
        }
        if (prior != null) running.remove(s.id());

        lastFireStartedAt.set(Instant.now());
        log.info("schedule '{}' firing -> {}", s.name(), s.action());
        String jarPath = locateCvectorJar();
        if (jarPath == null) {
            log.warn("schedule '{}' would have run `cvector {}` but jar path is unknown (running from IDE or classpath?)",
                    s.name(), normalizedAction(s.action()));
            return;
        }
        try {
            String normalized = normalizedAction(s.action());
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", jarPath, normalized);
            pb.inheritIO();
            Process p = pb.start();
            running.put(s.id(), p);
            final long pidForStart = p.pid();
            events.ifAvailable(pub -> pub.publishEvent(ScanStatusChangedEvent.started(normalized, pidForStart)));
            // onExit cleanup: remove from the live set when the subprocess finishes so the
            // next fire is free to proceed. Daemon thread, no need to join.
            p.onExit().whenComplete((proc, err) -> {
                running.remove(s.id(), proc);
                int exitCode;
                try { exitCode = proc.exitValue(); }
                catch (IllegalThreadStateException ex) { exitCode = -1; }
                final int exitForEvent = exitCode;
                events.ifAvailable(pub -> {
                    pub.publishEvent(ScanStatusChangedEvent.finished(normalized, proc.pid(), exitForEvent));
                    pub.publishEvent(GraphMutatedEvent.fromSchedule());
                });
            });
        } catch (Exception e) {
            log.error("schedule '{}' failed to start subprocess: {}", s.name(), e.getMessage());
        }
    }

    private static String normalizedAction(String a) {
        // The CLI's incremental command uses a colon; the dashboard UI lets the user pick
        // a hyphenated form too. Normalise here so the spawned subprocess gets a flag it
        // recognises.
        return "scan-incremental".equals(a) ? "scan:incremental" : a;
    }

    /**
     * Best-effort lookup of the path to the cvector fat jar that's currently running.
     * Uses Spring Boot's "java -jar" cue from sun.java.command. Returns null when not
     * running from a packaged jar (IDE, tests, embedded contexts).
     */
    private static String locateCvectorJar() {
        String jarCommand = System.getProperty("sun.java.command", "");
        if (jarCommand.isBlank()) return null;
        // sun.java.command looks like "C:/.../cvector.jar dashboard --open" -- take the
        // first token, drop the trailing argv.
        String token = jarCommand.split("\\s+", 2)[0];
        if (!token.toLowerCase().endsWith(".jar")) return null;
        File jar = new File(token);
        return jar.isFile() ? jar.getAbsolutePath() : null;
    }

    /** Diagnostic accessor (could be exposed via /api/dashboard/scheduler if useful). */
    public Instant lastFireStartedAt() { return lastFireStartedAt.get(); }

    public int activeCount() { return active.size(); }

    /**
     * Per-schedule snapshot for the dashboard's live indicators. Each entry is
     * {@code {registered, running, pid?, exitCode?}} so the UI can render "currently
     * running" badges and a "last exit code" hint without polling subprocess state itself.
     */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeTriggers", active.size());
        Instant last = lastFireStartedAt.get();
        out.put("lastFireStartedAt", last != null ? last.toString() : null);
        Map<String, Map<String, Object>> perSchedule = new LinkedHashMap<>();
        // Union of currently-registered ids and currently-running ids so the UI sees the
        // full set even when a schedule was just removed but its subprocess hasn't exited.
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        ids.addAll(active.keySet());
        ids.addAll(running.keySet());
        for (String id : ids) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("registered", active.containsKey(id));
            Process p = running.get(id);
            boolean alive = p != null && p.isAlive();
            s.put("running", alive);
            if (p != null) {
                s.put("pid", p.pid());
                if (!alive) {
                    try { s.put("exitCode", p.exitValue()); } catch (IllegalThreadStateException ignore) {}
                }
            }
            perSchedule.put(id, s);
        }
        out.put("schedules", perSchedule);
        return out;
    }

    /**
     * Provides a default TaskScheduler bean if Spring Boot didn't already wire one. Using
     * a small pool keeps cron registration cheap; actual scan work happens in a spawned
     * subprocess so this pool never sees long-running tasks.
     */
    @EnableScheduling
    @org.springframework.context.annotation.Configuration
    static class SchedulerConfig {
        @Bean(name = "dashboardTaskScheduler")
        public TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler t = new ThreadPoolTaskScheduler();
            t.setPoolSize(2);
            t.setThreadNamePrefix("cv-cron-");
            t.setDaemon(true);
            t.initialize();
            return t;
        }
    }
}
