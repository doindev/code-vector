package io.doindev.cvector.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Async {@code cvector diff} subprocess for the MCP {@code cv_diff_*} tools. Mirrors the
 * dashboard's {@code DiffRunner} but lives in the MCP module so the tool surface doesn't take
 * a cvector-dashboard dependency. Single-tenant (one diff at a time); subsequent start calls
 * with an in-flight diff return {@code ok:false, reason:"already-running"}.
 *
 * <p>The class is intentionally static — there's exactly one MCP server per JVM, and
 * concurrent diffs against the same git worktree would corrupt each other anyway. Output is
 * captured into a bounded 2 MB buffer; anything beyond gets a "[output truncated]" marker.
 */
final class CvectorDiffSubprocess {

    private static final int OUTPUT_CAP_BYTES = 2 * 1024 * 1024;
    private static final Pattern SHA_OR_REF = Pattern.compile("[A-Za-z0-9_./~^@-]+");

    private static final AtomicReference<Process> CURRENT = new AtomicReference<>();
    private static final AtomicReference<Instant> STARTED_AT = new AtomicReference<>();
    private static final AtomicReference<String> CURRENT_SHA_A = new AtomicReference<>();
    private static final AtomicReference<String> CURRENT_SHA_B = new AtomicReference<>();
    private static final AtomicReference<StringBuilder> CURRENT_OUTPUT = new AtomicReference<>();
    private static final AtomicReference<Instant> LAST_FINISHED_AT = new AtomicReference<>();
    private static final AtomicReference<Integer> LAST_EXIT_CODE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_OUTPUT = new AtomicReference<>();
    private static final AtomicReference<String> LAST_SHA_A = new AtomicReference<>();
    private static final AtomicReference<String> LAST_SHA_B = new AtomicReference<>();

    private CvectorDiffSubprocess() {}

    static synchronized Map<String, Object> start(String shaA, String shaB, boolean includeCalls, boolean keep) {
        if (shaA == null || !SHA_OR_REF.matcher(shaA).matches()) return fail("invalid-sha-a");
        if (shaB == null || !SHA_OR_REF.matcher(shaB).matches()) return fail("invalid-sha-b");
        Process existing = CURRENT.get();
        if (existing != null && existing.isAlive()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("reason", "already-running");
            out.put("pid", existing.pid());
            out.put("shaA", CURRENT_SHA_A.get());
            out.put("shaB", CURRENT_SHA_B.get());
            return out;
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
            CURRENT.set(p);
            STARTED_AT.set(Instant.now());
            CURRENT_SHA_A.set(shaA);
            CURRENT_SHA_B.set(shaB);
            StringBuilder buf = new StringBuilder();
            CURRENT_OUTPUT.set(buf);

            Thread drainer = new Thread(() -> drain(p, buf), "mcp-diff-drain-" + p.pid());
            drainer.setDaemon(true);
            drainer.start();

            p.onExit().whenComplete((proc, err) -> {
                LAST_FINISHED_AT.set(Instant.now());
                try { LAST_EXIT_CODE.set(proc.exitValue()); } catch (IllegalThreadStateException ignored) { /* unreachable */ }
                LAST_OUTPUT.set(buf.toString());
                LAST_SHA_A.set(shaA);
                LAST_SHA_B.set(shaB);
                CURRENT.compareAndSet(proc, null);
                STARTED_AT.set(null);
                CURRENT_SHA_A.set(null);
                CURRENT_SHA_B.set(null);
                CURRENT_OUTPUT.set(null);
            });

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pid", p.pid());
            out.put("startedAt", Instant.now().toString());
            out.put("shaA", shaA);
            out.put("shaB", shaB);
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "subprocess-failed", "message", String.valueOf(e.getMessage()));
        }
    }

    static Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Process p = CURRENT.get();
        boolean running = p != null && p.isAlive();
        out.put("running", running);
        if (running) {
            out.put("pid", p.pid());
            Instant start = STARTED_AT.get();
            if (start != null) {
                out.put("startedAt", start.toString());
                out.put("elapsedMillis", Duration.between(start, Instant.now()).toMillis());
            }
            out.put("shaA", CURRENT_SHA_A.get());
            out.put("shaB", CURRENT_SHA_B.get());
            StringBuilder buf = CURRENT_OUTPUT.get();
            if (buf != null) {
                synchronized (buf) {
                    out.put("partialOutput", buf.toString());
                    out.put("outputBytes", buf.length());
                }
            }
        }
        Instant last = LAST_FINISHED_AT.get();
        if (last != null) {
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("finishedAt", last.toString());
            prev.put("exitCode", LAST_EXIT_CODE.get());
            prev.put("shaA", LAST_SHA_A.get());
            prev.put("shaB", LAST_SHA_B.get());
            prev.put("output", LAST_OUTPUT.get() == null ? "" : LAST_OUTPUT.get());
            out.put("last", prev);
        }
        return out;
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

    private static String locateCvectorJar() {
        String jarCommand = System.getProperty("sun.java.command", "");
        if (jarCommand.isBlank()) return null;
        String token = jarCommand.split("\\s+", 2)[0];
        if (!token.toLowerCase().endsWith(".jar")) return null;
        File jar = new File(token);
        return jar.isFile() ? jar.getAbsolutePath() : null;
    }

    private static Map<String, Object> fail(String reason) {
        return Map.of("ok", false, "reason", reason);
    }
}
