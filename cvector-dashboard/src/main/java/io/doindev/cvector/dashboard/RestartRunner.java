package io.doindev.cvector.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process restart for the dashboard. The UI's Settings view triggers this when the user
 * changes a setting that requires a new JVM (REST port, host, backend, MCP transport). The
 * flow:
 * <ol>
 *   <li>Spawn a detached child JVM running the same {@code java -jar cvector.jar dashboard}
 *       command, in the same working directory. The parent's PID is passed as
 *       {@code -Dcvector.restart.waitForPid=<pid>} so the child can wait for the
 *       parent to fully exit (and release the Kuzu directory lock) before booting
 *       Spring. See {@link io.doindev.cvector.CvectorApplication#awaitParentExit}.</li>
 *   <li>Reply 202 Accepted to the caller so the browser knows the restart kicked off.</li>
 *   <li>Schedule a graceful Spring shutdown ~700 ms later — long enough for the response to
 *       flush, short enough that the child's handshake-wait stays short on the happy path.
 *       Tomcat releases the listen socket and Kuzu releases its file lock during the
 *       Spring shutdown hook that {@code System.exit(0)} fires.</li>
 * </ol>
 *
 * <p>Detachment is platform-specific:
 * <ul>
 *   <li>Windows: {@code powershell.exe -NoProfile -WindowStyle Hidden -Command Start-Process
 *       -FilePath 'java' -ArgumentList … -WindowStyle Hidden}. Earlier versions used
 *       {@code cmd /c start "" /B} but that tethers the child to the parent's console
 *       handle on modern Windows — the child dies when the parent exits. PowerShell's
 *       {@code Start-Process} creates a properly detached process that survives parent
 *       death; the extra ~300 ms PS-boot cost is fine for a once-per-restart flow.</li>
 *   <li>Linux/macOS: {@code sh -c "nohup java -jar … >/dev/null 2>&1 &"} — {@code nohup} +
 *       background redirection makes the child immune to the parent's exit.</li>
 * </ul>
 *
 * <p>Idempotency: a second restart while one is in flight returns {@code already-restarting}
 * so the UI doesn't accidentally fork two replacement JVMs.
 */
@Component
public class RestartRunner {

    private static final Logger log = LoggerFactory.getLogger(RestartRunner.class);

    /** How long we wait before initiating shutdown — gives the HTTP response time to flush. */
    private static final long SHUTDOWN_DELAY_MILLIS = 700;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant requestedAt;

    public RestartRunner() {
        // Empty — the ApplicationContext injection we used to take was dropped because we
        // can't safely call SpringApplication.exit() from a scheduled task (the context can
        // already be in the closing phase by then, throwing IllegalStateException). System.exit
        // runs shutdown hooks for us; Tomcat + Kuzu both register hooks, so the file lock
        // releases cleanly.
    }

    public synchronized Map<String, Object> restart() {
        if (!inFlight.compareAndSet(false, true)) {
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("ok", false);
            already.put("reason", "already-restarting");
            already.put("requestedAt", requestedAt == null ? "" : requestedAt.toString());
            return already;
        }
        String jar = locateCvectorJar();
        if (jar == null) {
            inFlight.set(false);
            return Map.of("ok", false, "reason", "no-jar",
                    "hint", "restart works only when running from the packaged cvector.jar");
        }
        try {
            long parentPid = ProcessHandle.current().pid();
            Process child = spawnDetached(jar, parentPid);
            requestedAt = Instant.now();
            log.info("restart: spawned replacement pid={} from jar={} (parent pid={})",
                    child.pid(), jar, parentPid);

            // Schedule the parent shutdown after a brief delay so the 202 response can flush
            // before the JVM goes away. Using SpringApplication.exit ensures Spring lifecycle
            // hooks (Tomcat close, Kuzu close) run in order — System.exit alone would skip
            // them and leak the Kuzu file lock for a few seconds.
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cvector-restart-shutdown");
                t.setDaemon(false);
                return t;
            });
            scheduler.schedule(() -> {
                log.info("restart: initiating graceful shutdown");
                // Plain System.exit triggers Spring's registered shutdown hook which closes the
                // context cleanly (Tomcat release, Kuzu close). SpringApplication.exit(context,…)
                // here races with Tomcat already closing the context from the request thread —
                // throws IllegalStateException. Simpler is better.
                System.exit(0);
            }, SHUTDOWN_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            scheduler.shutdown();

            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("pid", child.pid());
            ok.put("requestedAt", requestedAt.toString());
            ok.put("shutdownInMillis", SHUTDOWN_DELAY_MILLIS);
            return ok;
        } catch (Exception e) {
            inFlight.set(false);
            log.error("restart: failed to spawn replacement: {}", e.getMessage());
            return Map.of("ok", false, "reason", "spawn-failed",
                    "message", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /**
     * Build the detach command for the current platform and exec it. The {@code parentPid}
     * is threaded through as a JVM system property ({@code cvector.restart.waitForPid}) so
     * the child can wait for the parent to fully exit (and release the Kuzu directory lock)
     * before opening its own Kuzu handle — see
     * {@link io.doindev.cvector.CvectorApplication#awaitParentExit}.
     *
     * <p>Windows: invoked via PowerShell because {@code cmd /c start /B} tethers the child
     * to the parent's console on modern Windows; PowerShell's {@code Start-Process
     * -WindowStyle Hidden} cleanly creates a console-less process that survives the parent.
     * The ~300 ms PowerShell boot cost is acceptable for a once-per-restart flow.
     *
     * <p>Unix: {@code nohup java -jar … >/dev/null 2>&1 &} — standard daemon-fork pattern.
     */
    private static Process spawnDetached(String jar, long parentPid) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        String waitFlag = "-Dcvector.restart.waitForPid=" + parentPid;
        List<String> cmd = new ArrayList<>();
        if (windows) {
            // PowerShell quoting: each ArgumentList entry is a separate token; we wrap the
            // jar path in single quotes inside PS so spaces in the install path don't break.
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
            cmd.add("-Command");
            cmd.add("Start-Process -FilePath 'java' -ArgumentList '" + waitFlag + "','-jar','"
                    + jar.replace("'", "''") + "','dashboard' -WindowStyle Hidden");
        } else {
            cmd.add("sh");
            cmd.add("-c");
            cmd.add("nohup java " + waitFlag + " -jar '" + jar.replace("'", "'\\''")
                    + "' dashboard >/dev/null 2>&1 &");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(Paths.get("").toAbsolutePath().toFile());
        // Discard the launcher's own stdio — we don't need its output and inheriting would
        // keep the launcher alive longer than needed.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /** Same approach DiffRunner uses — pull the jar path from {@code sun.java.command}. */
    private static String locateCvectorJar() {
        String jarCommand = System.getProperty("sun.java.command", "");
        if (jarCommand.isBlank()) return null;
        String token = jarCommand.split("\\s+", 2)[0];
        if (!token.toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
        File jar = new File(token);
        return jar.isFile() ? jar.getAbsolutePath() : null;
    }

    /** Marker used by the UI banner to indicate a restart is currently in flight. */
    public boolean inFlight() { return inFlight.get(); }

    /** Suppress an unused-private-field warning — also handy for {@link Path}-based logging variants later. */
    @SuppressWarnings("unused")
    private Path workingDir() {
        return Paths.get("").toAbsolutePath();
    }
}
