package io.doindev.cvector;

import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.mcp.JobRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the cross-cutting concurrency invariants the dashboard relies on when
 * multiple MCP tool calls arrive in parallel. Each test isolates a single guarantee:
 *
 * <ul>
 *   <li>{@link EmbeddedKuzu}'s single {@link com.kuzudb.Connection} can be hammered from many
 *       threads without crashing or dropping data — the in-class {@code connLock} serialises
 *       native calls correctly.</li>
 *   <li>{@link JobRegistry}'s scan-claim map rejects duplicate scans for the same projectId
 *       and releases the slot when work completes (sync) or when {@link JobRegistry.Job}
 *       finishes (async).</li>
 *   <li>{@link JobRegistry} bounds runaway async jobs: a job that exceeds its deadline gets
 *       flipped to {@code FAILED} by the watchdog so callers don't see {@code RUNNING}
 *       forever.</li>
 * </ul>
 *
 * <p>These run as pure JUnit unit tests against in-process Kuzu — no Spring context, no
 * subprocess, no MCP transport. The HTTP/SSE plumbing has its own tests; this IT covers the
 * core data-layer + job-manager guarantees the agent-facing surface depends on.
 */
class MultiAgentConcurrencyIT {

    private static final String PID = "test-project";

    /**
     * Eight threads pounding the same connection with mixed reads and writes shouldn't crash
     * Kuzu's native layer or interleave results. Pre-fix, concurrent {@code execute()} calls
     * on the shared {@link com.kuzudb.Connection} either deadlocked or silently corrupted the
     * SDK state — manifested in the field as 4 parallel {@code cv_explain} calls timing out
     * at the client's 30 s ceiling and the entire MCP session dying.
     */
    @Test
    void concurrentReadsAndWritesAllSucceed(@TempDir Path tmp) throws Exception {
        try (EmbeddedKuzu kuzu = new EmbeddedKuzu(tmp.resolve("graph.kuzu"))) {
            new KuzuSchemaBootstrap(kuzu).bootstrap();
            KuzuGraphStore store = new KuzuGraphStore(kuzu);

            // Seed a tiny graph so reads return something interesting.
            seed(kuzu, PID, 50);

            int threadCount = 8;
            int opsPerThread = 30;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    final int id = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failures.incrementAndGet();
                            return;
                        }
                        for (int op = 0; op < opsPerThread; op++) {
                            try {
                                // Mix reads with the occasional small write so the lock has
                                // contention from both sides.
                                if (op % 5 == id % 5) {
                                    kuzu.write(
                                            "MERGE (n:Node {id: $id}) "
                                                    + "SET n.projectId = $pid, n.label = 'Method', "
                                                    + "    n.fqName = $fq, n.lastIngestedAt = timestamp('2024-01-01T00:00:00')",
                                            Map.of("id", "stress-" + id + "-" + op,
                                                    "pid", PID,
                                                    "fq", "stress.t" + id + ".m" + op));
                                } else {
                                    Map<String, Long> counts = store.nodeCounts(PID);
                                    if (counts == null) failures.incrementAndGet();
                                }
                            } catch (Throwable t) {
                                failures.incrementAndGet();
                            }
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                        .as("stress workers should finish within 60 s; if this trips it's the lock-contention regression coming back")
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(failures.get())
                    .as("no thread should have observed an exception under concurrent access")
                    .isZero();

            // Every write batch should have made it through and be queryable.
            Map<String, Long> finalCounts = store.nodeCounts(PID);
            assertThat(finalCounts.getOrDefault("Method", 0L))
                    .as("at least the seed methods plus some stress writes should be present")
                    .isGreaterThan(50L);
        }
    }

    /**
     * Two threads racing to claim a sync scan for the same projectId: exactly one wins.
     * Release-and-re-claim works after the first finishes.
     */
    @Test
    void duplicateScanClaimsAreRejected() {
        JobRegistry registry = new JobRegistry();
        try {
            assertThat(registry.tryBeginSyncScan(PID)).as("first claim wins").isTrue();
            assertThat(registry.tryBeginSyncScan(PID)).as("second claim while first holds").isFalse();
            assertThat(registry.findActiveScan(PID))
                    .as("active scan visible to find()")
                    .isPresent()
                    .hasValueSatisfying(claim -> {
                        assertThat(claim.projectId()).isEqualTo(PID);
                        assertThat(claim.async()).isFalse();
                        assertThat(claim.jobId()).as("sync scans have no jobId").isNull();
                    });

            registry.releaseScan(PID);
            assertThat(registry.tryBeginSyncScan(PID)).as("claim after release").isTrue();
        } finally {
            registry.releaseScan(PID);
            shutdown(registry);
        }
    }

    /**
     * Async scan submission while a sync scan is already in flight is rejected at the
     * registry boundary (the caller gets {@code Optional.empty()} and converts it to a
     * scanInProgress envelope upstream).
     */
    @Test
    void asyncSubmitBlockedWhileSyncScanHoldsClaim() throws Exception {
        JobRegistry registry = new JobRegistry();
        try {
            assertThat(registry.tryBeginSyncScan(PID)).isTrue();
            var submission = registry.submitScan(PID, "cv_scan_project", () -> Map.of("ok", true));
            assertThat(submission)
                    .as("submitScan must refuse when a claim already exists")
                    .isEmpty();
        } finally {
            registry.releaseScan(PID);
            shutdown(registry);
        }
    }

    /**
     * Async scan submission registers its claim; concurrent submissions for the SAME project
     * lose the race and get an empty Optional back. Different projects don't block each other.
     */
    @Test
    void asyncSubmitsCoexistAcrossProjects() throws Exception {
        JobRegistry registry = new JobRegistry();
        try {
            // Hold a long-running supplier for project A so its claim is observable.
            CountDownLatch latchA = new CountDownLatch(1);
            Optional<JobRegistry.Job> jobA = registry.submitScan("project-A", "cv_scan_project", () -> {
                try { latchA.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return Map.of("ok", true);
            });
            assertThat(jobA).as("first project-A claim").isPresent();

            // Second submission for the SAME project must be rejected while A is running.
            Optional<JobRegistry.Job> jobADup = registry.submitScan("project-A", "cv_scan_project", () -> Map.of("ok", true));
            assertThat(jobADup).as("duplicate claim on same project rejected").isEmpty();

            // A DIFFERENT project should succeed in parallel.
            Optional<JobRegistry.Job> jobB = registry.submitScan("project-B", "cv_scan_project", () -> Map.of("ok", true));
            assertThat(jobB).as("parallel claim on different project").isPresent();

            // Release A and wait for the slot to clear by polling state (the Job.future
            // accessor is package-private; agents observe completion via cv_job_status which
            // reads state — so the test mirrors that path).
            latchA.countDown();
            for (int i = 0; i < 40; i++) {
                if (jobA.get().state() != JobRegistry.State.RUNNING) break;
                Thread.sleep(50);
            }
            for (int i = 0; i < 20; i++) {
                if (registry.findActiveScan("project-A").isEmpty()) break;
                Thread.sleep(50);
            }
            assertThat(registry.findActiveScan("project-A"))
                    .as("project-A claim must release when its Job completes")
                    .isEmpty();
        } finally {
            shutdown(registry);
        }
    }

    /**
     * A job that exceeds its deadline gets force-failed by the watchdog. Best-effort cancel:
     * Kuzu native calls can't be interrupted, but the {@link JobRegistry.Job} record reaches
     * a terminal state so {@code cv_job_status} stops reporting {@code running} forever.
     */
    @Test
    void asyncJobTimeoutFlipsToFailed() throws Exception {
        JobRegistry registry = new JobRegistry();
        try {
            CountDownLatch hold = new CountDownLatch(1);
            JobRegistry.Job job = registry.submit("cv_test_runaway", () -> {
                try { hold.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return Map.of("ok", true);
            }, Duration.ofMillis(300));

            // Watchdog deadline is 300 ms; give it generous slack.
            for (int i = 0; i < 30; i++) {
                if (job.state() != JobRegistry.State.RUNNING) break;
                Thread.sleep(100);
            }

            assertThat(job.state())
                    .as("job that overruns its deadline must be flipped to FAILED")
                    .isEqualTo(JobRegistry.State.FAILED);
            assertThat(job.error()).isNotNull();
            // Release the worker so the executor shuts down cleanly.
            hold.countDown();
        } finally {
            shutdown(registry);
        }
    }

    /**
     * {@link JobRegistry#stats()} returns running + total counters + the default timeout —
     * exactly the fields {@code cv_health} surfaces.
     */
    @Test
    void statsCounterReflectsRunningAndTerminalJobs() throws Exception {
        JobRegistry registry = new JobRegistry();
        try {
            JobRegistry.Stats empty = registry.stats();
            assertThat(empty.running()).isZero();
            assertThat(empty.total()).isZero();
            assertThat(empty.defaultTimeout().toMinutes()).isPositive();

            CountDownLatch hold = new CountDownLatch(1);
            JobRegistry.Job j = registry.submit("cv_test", () -> {
                try { hold.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return Map.of("ok", true);
            });

            // Brief wait for executor to pick up the task.
            for (int i = 0; i < 20; i++) {
                if (registry.stats().running() == 1) break;
                Thread.sleep(25);
            }
            JobRegistry.Stats running = registry.stats();
            assertThat(running.running()).isEqualTo(1);
            assertThat(running.total()).isEqualTo(1);

            hold.countDown();
            for (int i = 0; i < 40; i++) {
                if (j.state() != JobRegistry.State.RUNNING) break;
                Thread.sleep(50);
            }
            for (int i = 0; i < 20; i++) {
                if (registry.stats().running() == 0) break;
                Thread.sleep(25);
            }
            JobRegistry.Stats done = registry.stats();
            assertThat(done.running()).isZero();
            assertThat(done.total()).isEqualTo(1);
        } finally {
            shutdown(registry);
        }
    }

    // -----------------------------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------------------------

    private static void seed(EmbeddedKuzu kuzu, String pid, int methodCount) {
        for (int i = 0; i < methodCount; i++) {
            kuzu.write(
                    "MERGE (n:Node {id: $id}) "
                            + "SET n.projectId = $pid, n.label = 'Method', "
                            + "    n.fqName = $fq, n.lastIngestedAt = timestamp('2024-01-01T00:00:00')",
                    Map.of("id", "seed-" + i,
                            "pid", pid,
                            "fq", "seed.M" + i));
        }
    }

    /**
     * JobRegistry's @PreDestroy hook only fires under Spring; from a plain JUnit test we have
     * to shut down the executors manually so worker threads don't leak across tests.
     */
    private static void shutdown(JobRegistry registry) {
        try {
            java.lang.reflect.Method m = JobRegistry.class.getDeclaredMethod("shutdown");
            m.setAccessible(true);
            m.invoke(registry);
        } catch (ReflectiveOperationException e) {
            // Best-effort — if the method ever moves, the JVM exit will clean up daemons.
        }
    }
}
