package io.doindev.cvector.mcp;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * In-memory registry of background MCP tool jobs. When an MCP client passes
 * {@code async: true} to a long-running tool (cv_scan_project, cv_onboard_project,
 * cv_purge_orphans, etc.) the tool registers a {@link Job} here, returns the job id
 * synchronously, and the work runs on a bounded background executor. The client polls
 * {@code cv_job_status(jobId)} until the job reaches a terminal state.
 *
 * <p><b>Why this exists.</b> Spring AI's WebMVC MCP transport blocks the request thread on
 * {@code session.handle(message).block()} until the tool returns. A scan or purge that
 * takes longer than the client's HTTP timeout (typically 30 s) appears as a hang to the
 * agent — and even when the work completes server-side, the response is held behind the
 * already-disconnected client. Async mode side-steps that entirely: the tool returns in
 * milliseconds with a job id, the work proceeds independently, and the agent polls until
 * it sees {@code state: "done"}. The work completes durably regardless of whether the
 * client is still listening.
 *
 * <p><b>Lifetime.</b> Jobs live in memory for the JVM's lifetime; nothing is persisted, so
 * a server restart loses in-flight jobs and any completed-but-unpolled results. Finished
 * jobs are kept for {@link #RETAIN_TERMINAL} (1 h) so an agent that polls slowly still
 * sees the result; older entries are evicted opportunistically on read.
 *
 * <p><b>Concurrency.</b> Backed by a fixed-size pool of 4 daemon threads. Most agent
 * traffic is sequential and the chunked-delete / in-process-scan optimisations keep
 * individual jobs short; cap exists to bound resource use if an LLM kicks off many
 * parallel destructive operations.
 */
@Component
@Profile("mcp")
public class JobRegistry {

    public enum State { RUNNING, DONE, FAILED }

    private static final Duration RETAIN_TERMINAL = Duration.ofHours(1);
    /**
     * Default per-job wall-clock cap. If a job is still {@code RUNNING} after this much
     * elapsed time, the watchdog flips it to {@code FAILED} with a "deadline exceeded"
     * error and tries to cancel the underlying {@link Future} (best-effort — Kuzu native
     * calls don't respond to {@code Thread.interrupt()}, so the executor thread may keep
     * running until the work completes naturally; the {@link Job} record still reflects
     * the failure so the caller can stop polling). The natural upper bound for a sane
     * cvector workload is way under this; raising it serves bigger codebases without
     * silently capping operations that should succeed.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    /**
     * Tracks at most one in-flight scan per projectId so two concurrent
     * {@code cv_scan_project} calls for the same project don't duplicate-write the same
     * graph data. The MERGE-based ingestor would tolerate it (writes are idempotent on
     * NodeKey hash), but it's wasted CPU + lock contention; the agent gets a clearer
     * envelope when the duplicate is rejected immediately. Sync scans register with a
     * null jobId; async scans register with their Job's UUID so the caller can poll the
     * existing run.
     */
    private final Map<String, ScanClaim> activeScans = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;

    public JobRegistry() {
        AtomicInteger ix = new AtomicInteger();
        ThreadFactory workTf = r -> {
            Thread t = new Thread(r, "cvector-mcp-job-" + ix.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(4, workTf);
        ThreadFactory wdTf = r -> {
            Thread t = new Thread(r, "cvector-mcp-job-watchdog");
            t.setDaemon(true);
            return t;
        };
        this.watchdog = Executors.newSingleThreadScheduledExecutor(wdTf);
    }

    /**
     * Register and start a new background job with the default 15-min timeout.
     */
    public Job submit(String kind, Supplier<Map<String, Object>> work) {
        return submit(kind, work, DEFAULT_TIMEOUT);
    }

    /**
     * Submit an async scan and atomically register the {@link ScanClaim}. Returns the new
     * Job if the claim succeeded; returns an empty Optional if another scan is already in
     * flight for the same project (the caller can then call {@link #findActiveScan} to
     * fetch the running claim and return its jobId to the agent).
     */
    public Optional<Job> submitScan(String projectId, String kind, Supplier<Map<String, Object>> work) {
        if (findActiveScan(projectId).isPresent()) return Optional.empty();
        Supplier<Map<String, Object>> wrapped = () -> {
            try {
                return work.get();
            } finally {
                activeScans.remove(projectId);
            }
        };
        Job job = submit(kind, wrapped);
        // putIfAbsent loses the race occasionally; on a race, both Jobs will run but the
        // ingestor's idempotent MERGE keeps data correctness. We're optimising for the
        // common case (no race), so accept the tiny cost.
        activeScans.putIfAbsent(projectId, new ScanClaim(projectId, job.id(), Instant.now(), true));
        return Optional.of(job);
    }

    /**
     * Register and start a new background job. The {@code work} supplier runs on the
     * executor; its return value becomes {@link Job#result()}, and any throwable becomes
     * {@link Job#error()}. If the job is still {@code RUNNING} after {@code timeout},
     * the watchdog marks it {@code FAILED} and best-effort cancels the underlying Future.
     */
    public Job submit(String kind, Supplier<Map<String, Object>> work, Duration timeout) {
        UUID id = UUID.randomUUID();
        Job job = new Job(id, kind, Instant.now());
        jobs.put(id, job);
        job.future = executor.submit(() -> {
            try {
                Map<String, Object> result = work.get();
                job.complete(result);
            } catch (Throwable t) {
                job.fail(t);
            }
        });
        Duration effective = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        watchdog.schedule(() -> expireIfStillRunning(job, effective),
                effective.toMillis(), TimeUnit.MILLISECONDS);
        return job;
    }

    private void expireIfStillRunning(Job job, Duration timeout) {
        if (job.state() != State.RUNNING) return;
        // Build a structured deadline-exceeded error that surfaces cleanly through
        // cv_job_status without depending on an actual Throwable. The Future cancel is
        // best-effort: a Kuzu native call in flight ignores interrupts, but the Job state
        // change is enough for the polling client to stop hoping.
        job.fail(new java.util.concurrent.TimeoutException(
                "job exceeded its " + timeout.toSeconds() + "s deadline and was marked failed; "
                        + "the background work may still be running until it completes naturally"));
        if (job.future != null) {
            try { job.future.cancel(true); } catch (RuntimeException ignored) { /* best-effort */ }
        }
    }

    /** Look up a job by id, returning {@code null} if absent or already evicted. */
    public Job get(UUID id) {
        evictStale();
        return jobs.get(id);
    }

    /** Current registry contents (caller-modifiable copy). Used by the dashboard / debugging tools. */
    public Map<UUID, Job> snapshot() {
        evictStale();
        return new LinkedHashMap<>(jobs);
    }

    /** Quick stats for cv_health. */
    public Stats stats() {
        evictStale();
        int running = 0;
        for (Job j : jobs.values()) {
            if (j.state() == State.RUNNING) running++;
        }
        return new Stats(running, jobs.size(), DEFAULT_TIMEOUT);
    }

    public record Stats(int running, int total, Duration defaultTimeout) {}

    // ===========================================================================================
    //  Concurrent-scan guard
    // ===========================================================================================

    /**
     * In-flight scan record. {@code jobId} is non-null for async scans (so the caller can
     * poll the existing run) and null for sync scans (which have no Job in the registry —
     * they're held by the request handler thread).
     */
    public record ScanClaim(String projectId, UUID jobId, Instant startedAt, boolean async) {}

    /**
     * If a scan is already running for {@code projectId}, return its claim. Stale entries
     * (a previous claim whose associated Job is no longer {@code RUNNING}) are GC'd
     * opportunistically here, so a crashed scan thread can't permanently block future
     * attempts.
     */
    public Optional<ScanClaim> findActiveScan(String projectId) {
        ScanClaim claim = activeScans.get(projectId);
        if (claim == null) return Optional.empty();
        if (claim.jobId() != null) {
            Job j = jobs.get(claim.jobId());
            if (j == null || j.state() != State.RUNNING) {
                activeScans.remove(projectId, claim);
                return Optional.empty();
            }
        }
        return Optional.of(claim);
    }

    /**
     * Atomically register a sync scan for {@code projectId}. Returns {@code true} when the
     * caller now owns the claim and must call {@link #releaseScan} when done; {@code false}
     * when another scan is already in flight.
     */
    public boolean tryBeginSyncScan(String projectId) {
        return activeScans.putIfAbsent(projectId, new ScanClaim(projectId, null, Instant.now(), false)) == null;
    }

    /**
     * Release a previously-claimed scan slot. Idempotent; safe to call from a finally
     * block whether or not the original {@code tryBegin*} succeeded.
     */
    public void releaseScan(String projectId) {
        activeScans.remove(projectId);
    }

    private void evictStale() {
        Instant cutoff = Instant.now().minus(RETAIN_TERMINAL);
        jobs.entrySet().removeIf(e -> {
            Job j = e.getValue();
            return j.state() != State.RUNNING && j.finishedAt() != null && j.finishedAt().isBefore(cutoff);
        });
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Job {
        private final UUID id;
        private final String kind;
        private final Instant startedAt;
        private volatile State state = State.RUNNING;
        private volatile Instant finishedAt;
        private volatile Map<String, Object> result;
        private volatile Throwable error;
        Future<?> future;

        Job(UUID id, String kind, Instant startedAt) {
            this.id = id;
            this.kind = kind;
            this.startedAt = startedAt;
        }

        public UUID id() { return id; }
        public String kind() { return kind; }
        public Instant startedAt() { return startedAt; }
        public State state() { return state; }
        public Instant finishedAt() { return finishedAt; }
        public Map<String, Object> result() { return result; }
        public Throwable error() { return error; }

        void complete(Map<String, Object> result) {
            this.result = result;
            this.finishedAt = Instant.now();
            this.state = State.DONE;
        }

        void fail(Throwable error) {
            this.error = error;
            this.finishedAt = Instant.now();
            this.state = State.FAILED;
        }

        public long elapsedMs() {
            Instant end = finishedAt != null ? finishedAt : Instant.now();
            return Duration.between(startedAt, end).toMillis();
        }
    }
}
