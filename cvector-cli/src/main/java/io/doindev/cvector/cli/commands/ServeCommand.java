package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Component
@Command(name = "serve", description = "Run the cvector MCP server over stdio (for IDE integration).")
public class ServeCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public ServeCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws InterruptedException {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        System.err.println("cvector mcp server (stdio) ready");
        System.err.println("  project: " + active.name() + " (" + active.projectId() + ")");
        System.err.println("  neo4j:   " + cfg.neo4j().uri());
        System.err.println("  log:     " + System.getProperty("user.home") + "/.cvector/mcp-server.log");

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown, "cvector-mcp-shutdown"));
        shutdown.await();
        return 0;
    }
}
