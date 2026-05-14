package io.doindev.cvector.dashboard;

import io.doindev.cvector.rest.GraphMutatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-tenant async runner for {@code cvector diff <shaA> <shaB>}. Mirrors {@link ScanRunner}'s
 * fork-a-subprocess + capture-status pattern but tailored to the diff command, which can run for
 * minutes (it scans two git worktrees), needs structured arguments (two SHAs + optional flags),
 * and produces a human-readable output the dashboard renders verbatim.
 *
 * <p>Output is captured into a bounded in-memory buffer (cap 2 MB so a runaway diff can't OOM
 * the dashboard JVM). The runner doesn't try to parse the text — DiffCommand's stdout is the
 * canonical UX surface; the dashboard renders it as a monospace block.
 */
@Component
public class DiffRunner {

    private static final Logger log = LoggerFactory.getLogger(DiffRunner.class);

    /** Output cap. 2 MB is plenty for a "files added / removed / version-changed" diff. */
    private static final int OUTPUT_CAP_BYTES = 2 * 1024 * 1024;

    /** Hex char regex — every SHA argument has to match before we shell out. */
    private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("[0-9a-fA-F]{4,40}");

    private final AtomicReference<Process> current = new AtomicReference<>();
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<String> currentShaA = new AtomicReference<>();
    private final AtomicReference<String> currentShaB = new AtomicReference<>();
    private final AtomicReference<StringBuilder> currentOutput = new AtomicReference<>();
    private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>();
    private final AtomicReference<Integer> lastExitCode = new AtomicReference<>();
    private final AtomicReference<String> lastOutput = new AtomicReference<>();
    private final AtomicReference<String> lastShaA = new AtomicReference<>();
    private final AtomicReference<String> lastShaB = new AtomicReference<>();

    private final ObjectProvider<ApplicationEventPublisher> events;

    public DiffRunner(ObjectProvider<ApplicationEventPublisher> events) {
        this.events = events;
    }

    /** Start a diff. {@code includeCalls} maps to DiffCommand's {@code --include-calls}. */
    public synchronized Map<String, Object> start(String shaA, String shaB, boolean includeCalls, boolean keep) {
        if (shaA == null || !SHA.matcher(shaA).matches()) return fail("invalid-sha-a");
        if (shaB == null || !SHA.matcher(shaB).matches()) return fail("invalid-sha-b");
        Process existing = current.get();
        if (existing != null && existing.isAlive()) {
            return Map.of("ok", false, "reason", "already-running",
                    "pid", existing.pid(),
                    "shaA", currentShaA.get() == null ? "" : currentShaA.get(),
                    "shaB", currentShaB.get() == null ? "" : currentShaB.get());
        }
        String jar = locateCvectorJar();
        if (jar == null) return fail("no-jar");

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jar);
        cmd.add("diff");
        cmd.add(shaA);
        cmd.add(shaB);
        if (includeCalls) cmd.add("--include-calls");
        if (keep) cmd.add("--keep");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            current.set(p);
            startedAt.set(Instant.now());
            currentShaA.set(shaA);
            currentShaB.set(shaB);
            StringBuilder buf = new StringBuilder();
            currentOutput.set(buf);
            log.info("dashboard-triggered diff started: pid={} {}..{}", p.pid(), shaA, shaB);

            // Drain stdout on a daemon thread so we don't fill the pipe buffer and deadlock
            // the child. Cap to OUTPUT_CAP_BYTES; anything beyond gets a "[output truncated]"
            // marker so the user knows we dropped data.
            Thread drainer = new Thread(() -> drain(p, buf), "diff-stdout-drain-" + p.pid());
            drainer.setDaemon(true);
            drainer.start();

            p.onExit().whenComplete((proc, err) -> {
                lastFinishedAt.set(Instant.now());
                try { lastExitCode.set(proc.exitValue()); } catch (IllegalThreadStateException ignore) { /* unreachable */ }
                lastOutput.set(buf.toString());
                lastShaA.set(shaA);
                lastShaB.set(shaB);
                current.compareAndSet(proc, null);
                startedAt.set(null);
                currentShaA.set(null);
                currentShaB.set(null);
                currentOutput.set(null);
                log.info("dashboard-triggered diff finished: pid={} exit={}", proc.pid(), lastExitCode.get());
                // A diff doesn't mutate the live project graph (snapshot pids stay isolated),
                // but downstream caches still benefit from a flush in case the user navigated
                // through stale aggregations during the long-running diff.
                events.ifAvailable(pub -> pub.publishEvent(GraphMutatedEvent.fromScan()));
            });

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pid", p.pid());
            out.put("startedAt", Instant.now().toString());
            out.put("shaA", shaA);
            out.put("shaB", shaB);
            return out;
        } catch (Exception e) {
            log.error("failed to start diff subprocess: {}", e.getMessage());
            return Map.of("ok", false, "reason", "subprocess-failed", "message", e.getMessage());
        }
    }

    /** Snapshot of the current and last completed diff. */
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
            out.put("shaA", currentShaA.get());
            out.put("shaB", currentShaB.get());
            StringBuilder buf = currentOutput.get();
            if (buf != null) {
                synchronized (buf) {
                    out.put("partialOutput", buf.toString());
                    out.put("outputBytes", buf.length());
                }
            }
        }
        Instant last = lastFinishedAt.get();
        if (last != null) {
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("finishedAt", last.toString());
            previous.put("exitCode", lastExitCode.get());
            previous.put("shaA", lastShaA.get());
            previous.put("shaB", lastShaB.get());
            previous.put("output", lastOutput.get() == null ? "" : lastOutput.get());
            out.put("last", previous);
        }
        return out;
    }

    private static Map<String, Object> fail(String reason) {
        return Map.of("ok", false, "reason", reason);
    }

    private static void drain(Process p, StringBuilder buf) {
        boolean truncated = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (buf) {
                    if (buf.length() + line.length() + 1 > OUTPUT_CAP_BYTES) {
                        if (!truncated) {
                            buf.append("\n[output truncated at ").append(OUTPUT_CAP_BYTES).append(" bytes]\n");
                            truncated = true;
                        }
                        continue;
                    }
                    buf.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // Process exit closes the pipe; that's how we know to stop reading.
        }
    }

    /** Same approach ScanRunner uses to discover the jar so the subprocess matches our binary. */
    private static String locateCvectorJar() {
        String jarCommand = System.getProperty("sun.java.command", "");
        if (jarCommand.isBlank()) return null;
        String token = jarCommand.split("\\s+", 2)[0];
        if (!token.toLowerCase().endsWith(".jar")) return null;
        File jar = new File(token);
        return jar.isFile() ? jar.getAbsolutePath() : null;
    }
}
