package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "flows", description = "Trace execution flows from entry points through the call graph.", mixinStandardHelpOptions = true)
public class FlowsCommand implements Callable<Integer> {

    @Option(names = "--max-depth", description = "Max BFS depth from each entry point (default 4).")
    private int maxDepth = 4;

    @Option(names = "--limit", description = "Max entry points to display (default 20).")
    private int limit = 20;

    @Option(names = "--kind", description = "Entry-point kind filter: rest, main, test, all (default all).")
    private String kind = "all";

    private final CvectorRuntime runtime;

    public FlowsCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            Map<String, List<Map<String, Object>>> flows =
                    store.traceFlows(active.projectId(), kind, Math.max(1, maxDepth), limit);
            if ("rest".equals(kind) || "all".equals(kind)) renderSection("REST endpoint flows", flows.get("rest"));
            if ("main".equals(kind) || "all".equals(kind)) renderSection("Main-method flows", flows.get("main"));
            if ("test".equals(kind) || "all".equals(kind)) renderSection("Test-method flows", flows.get("test"));
        }
        return 0;
    }

    private void renderSection(String title, List<Map<String, Object>> entries) {
        section(title);
        if (entries == null || entries.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        for (Map<String, Object> e : entries) {
            String entryLabel = entryLabelOf(e);
            String handler = e.get("handler") != null ? String.valueOf(e.get("handler")) : entryLabel;
            Object reachesObj = e.get("reaches");
            System.out.println();
            System.out.println("entry: " + entryLabel);
            if (e.get("handler") != null) System.out.println("  handler: " + handler);
            if (!(reachesObj instanceof List<?> reaches) || reaches.isEmpty()) {
                System.out.println("  reaches: (no downstream CALLS within depth " + maxDepth + ")");
                continue;
            }
            System.out.println("  reaches " + reaches.size() + " methods within depth " + maxDepth + ":");
            int shown = 0;
            for (Object r : reaches) {
                if (shown >= 8) {
                    System.out.println("    ... (" + (reaches.size() - shown) + " more)");
                    break;
                }
                System.out.println("    - " + r);
                shown++;
            }
        }
    }

    private static String entryLabelOf(Map<String, Object> e) {
        // For REST entries we built a "{method} {path}" label; main/test rows just carry an `entry` fqName.
        if (e.get("method") != null && e.get("path") != null) {
            return e.get("method") + " " + e.get("path");
        }
        return String.valueOf(e.getOrDefault("entry", e.get("handler")));
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }
}
