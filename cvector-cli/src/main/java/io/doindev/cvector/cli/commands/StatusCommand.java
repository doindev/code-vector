package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "status", description = "Show project graph statistics.", mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public StatusCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            if (!store.ping()) {
                System.err.println("graph backend unreachable at " + store.displayUri());
                return 1;
            }
            System.out.println("project:  " + active.name() + " (" + active.projectId() + ")");
            System.out.println("root:     " + active.rootPath());
            System.out.println("backend:  " + store.displayUri());
            System.out.println();
            System.out.println("nodes:");
            Map<String, Long> nodes = store.nodeCounts(active.projectId());
            if (nodes.isEmpty()) System.out.println("  (none)");
            nodes.forEach((label, c) -> System.out.printf("  %-12s %d%n", label, c));
            System.out.println();
            System.out.println("edges:");
            Map<String, Long> edges = store.edgeCounts(active.projectId());
            if (edges.isEmpty()) System.out.println("  (none)");
            edges.forEach((type, c) -> System.out.printf("  %-12s %d%n", type, c));
        }
        return 0;
    }
}
