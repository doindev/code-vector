package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Component
@Command(name = "dashboard", description = "Start the cvector REST API server (default port 2969).")
public class DashboardCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public DashboardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws InterruptedException {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        System.out.println("cvector dashboard running");
        System.out.println("  project:  " + active.name() + " (" + active.projectId() + ")");
        System.out.println("  neo4j:    " + cfg.neo4j().uri());
        System.out.println("  api:      http://localhost:2969/api");
        System.out.println();
        System.out.println("Endpoints:");
        for (String e : new String[]{
                "GET /api/health",
                "GET /api/stats",
                "GET /api/projects",
                "GET /api/search?q=<symbol>",
                "GET /api/explain?symbol=<symbol>",
                "GET /api/impact?symbol=<symbol>[&depth=N]",
                "GET /api/test-impact?symbol=<symbol>[&depth=N]"
        }) {
            System.out.println("  " + e);
        }
        System.out.println();
        System.out.println("Press Ctrl-C to stop.");

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
