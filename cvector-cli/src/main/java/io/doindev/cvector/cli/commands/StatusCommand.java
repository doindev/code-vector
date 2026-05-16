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
        // Empty-workspace tolerance: with no projects registered (and no --project override)
        // `requireActiveProject` throws. Status should still print something useful — the
        // workspace summary — so the user can confirm cvector found their settings.json
        // and then run `cvector init` or `cvector project create`.
        CvectorConfig.ProjectEntry active;
        try { active = runtime.requireActiveProject(cfg); }
        catch (RuntimeException ex) { active = null; }

        if (active == null) {
            System.out.println("workspace: " + cfg.projects().size() + " project(s) registered");
            if (cfg.projects().isEmpty()) {
                System.out.println("  (none — run `cvector init` from your codebase directory, or `cvector project create <name> --root <path>`)");
            } else {
                System.out.println("  no active project set. Use `cvector project switch <name>` or pass --project <name> on the next call.");
                System.out.println("  registered:");
                cfg.projects().forEach((name, p) ->
                        System.out.printf("    %-30s %s%n", name, p.rootPath()));
            }
            return 0;
        }

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
