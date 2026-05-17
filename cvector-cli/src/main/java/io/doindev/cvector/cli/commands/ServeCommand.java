package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
@Command(name = "serve",
        description = "Run the cvector MCP server over stdio (for IDE integration). Pass --open to also open the dashboard in the default browser; this assumes `cvector dashboard` is already running on the same machine.",
        mixinStandardHelpOptions = true)
public class ServeCommand implements Callable<Integer> {

    @Option(names = {"--open", "-o"},
            description = "Open the cvector dashboard URL in the default browser. Useful when an IDE pairs MCP with a dashboard visit (assumes `cvector dashboard` is running on the same host).")
    private boolean openBrowser;

    @Option(names = "--dashboard-url",
            description = "URL to open when --open is set (default http://localhost:2969/dashboard/).")
    private String dashboardUrl = "http://localhost:2969/dashboard/";

    private final CvectorRuntime runtime;

    public ServeCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws InterruptedException {
        CvectorConfig cfg = runtime.loadConfig();
        // Tolerate empty workspaces — the MCP server boots fine without an active project
        // so the agent can immediately call cv_add_project / cv_onboard_project. We try
        // requireActiveProject() best-effort; if it throws (no default + no override), we
        // print a placeholder banner that still confirms the server is up.
        CvectorConfig.ProjectEntry active;
        try { active = runtime.requireActiveProject(cfg); }
        catch (RuntimeException e) { active = null; }

        // Logs go to stderr so stdout is free for MCP JSON-RPC.
        System.err.println("cvector mcp server (stdio) ready");
        if (active != null) {
            System.err.println("  project:   " + active.name() + " (" + active.projectId() + ")");
        } else {
            System.err.println("  project:   <none registered — call cv_add_project or cv_onboard_project>");
        }
        System.err.println("  neo4j:     " + cfg.neo4jOrDefault().uri());
        System.err.println("  transport: stdio (System.in / System.out JSON-RPC)");
        System.err.println("  log:       " + System.getProperty("user.home") + "/.cvector/mcp-server.log");

        if (openBrowser) {
            // Best-effort: fire-and-forget on a daemon thread so a misbehaving Desktop API
            // can't block MCP. Failure is logged to stderr; serve continues regardless.
            Thread t = new Thread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create(dashboardUrl));
                        System.err.println("opened " + dashboardUrl + " in default browser");
                    } else {
                        System.err.println("(--open requested but Desktop API unavailable; visit " + dashboardUrl + " manually)");
                    }
                } catch (Exception e) {
                    System.err.println("failed to open browser: " + e.getMessage());
                }
            }, "cvector-serve-open");
            t.setDaemon(true);
            t.start();
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown, "cvector-mcp-shutdown"));
        shutdown.await();
        return 0;
    }
}
