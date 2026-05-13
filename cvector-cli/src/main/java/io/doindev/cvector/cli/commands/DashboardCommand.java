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
@Command(name = "dashboard",
        description = "Start the cvector REST API server (default port 2969). Pass --open to launch the dashboard UI in the default browser.",
        mixinStandardHelpOptions = true)
public class DashboardCommand implements Callable<Integer> {

    @Option(names = {"--open", "-o"},
            description = "Open the dashboard UI in the default browser once the server is up.")
    private boolean openBrowser;

    @Option(names = "--port",
            description = "Port to bind (default 2969). Overrides server.port from application.properties.")
    private Integer port;

    private final CvectorRuntime runtime;

    public DashboardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws InterruptedException {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        int effectivePort = port != null ? port : 2969;
        String backend = CvectorRuntime.isEmbeddedRequested()
                ? "kuzu (embedded)"
                : "neo4j @ " + (cfg.neo4j() != null ? cfg.neo4j().uri() : "default");
        String base = "http://localhost:" + effectivePort;
        System.out.println("cvector dashboard running");
        System.out.println("  project:    " + active.name() + " (" + active.projectId() + ")");
        System.out.println("  backend:    " + backend);
        System.out.println("  api:        " + base + "/api");
        System.out.println("  dashboard:  " + base + "/dashboard");
        System.out.println();
        System.out.println("Endpoints:");
        for (String e : new String[]{
                "GET /api/health",
                "GET /api/stats",
                "GET /api/projects",
                "GET /api/search?q=<symbol>",
                "GET /api/explain?symbol=<symbol>",
                "GET /api/impact?symbol=<symbol>[&depth=N]",
                "GET /api/test-impact?symbol=<symbol>[&depth=N]",
                "GET /api/dashboard/status"
        }) {
            System.out.println("  " + e);
        }
        System.out.println();
        System.out.println("Press Ctrl-C to stop.");

        if (openBrowser) {
            // Launch in a background thread so a slow browser open doesn't block the
            // shutdown latch below. The 600ms delay gives Tomcat a beat to bind the
            // port -- without it Chrome often races and shows ERR_CONNECTION_REFUSED.
            Thread opener = new Thread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(600);
                    URI url = URI.create(base + "/dashboard/");
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(url);
                        System.out.println("opened " + url + " in default browser");
                    } else {
                        System.out.println("(--open requested but headless / Desktop API unavailable; visit " + url + " manually)");
                    }
                } catch (Exception e) {
                    System.err.println("failed to open browser: " + e.getMessage());
                }
            }, "cvector-open-browser");
            opener.setDaemon(true);
            opener.start();
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("shutting down...");
            shutdown.countDown();
        }, "cvector-shutdown"));
        shutdown.await();
        return 0;
    }
}
