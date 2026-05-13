package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "onboard", description = "One-command codebase briefing from the active project's graph.", mixinStandardHelpOptions = true)
public class OnboardCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public OnboardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            String pid = active.projectId();
            renderProjectHeader(active, store);
            renderGraphSize(store, pid);
            Map<String, List<Map<String, Object>>> summary = store.onboardSummary(pid);
            section("LANGUAGES");
            print(summary.get("languages"));
            section("TOP 10 CLASSES (by method count)");
            print(summary.get("topClasses"));
            section("REST ENDPOINTS");
            print(summary.get("restEndpoints"));
            section("DATABASE TABLES");
            print(summary.get("tables"));
            section("CONFIG KEYS (top 50)");
            print(summary.get("configKeys"));
            section("ENV VARS");
            print(summary.get("envVars"));
            section("MAVEN DEPENDENCIES");
            print(store.mavenDependencies(pid));
            section("HIGHEST-DEGREE METHODS (call graph hubs)");
            print(summary.get("callGraphHubs"));
        }
        return 0;
    }

    private static void renderProjectHeader(CvectorConfig.ProjectEntry active, GraphStore store) {
        section("PROJECT");
        System.out.println("  name:    " + active.name());
        System.out.println("  root:    " + active.rootPath());
        System.out.println("  id:      " + active.projectId());
        System.out.println("  backend: " + store.displayUri());
    }

    private static void renderGraphSize(GraphStore store, String pid) {
        section("GRAPH SIZE");
        Map<String, Long> nodes = store.nodeCounts(pid);
        Map<String, Long> edges = store.edgeCounts(pid);
        long files = nodes.getOrDefault("File", 0L);
        long methods = nodes.getOrDefault("Method", 0L);
        long classes = nodes.getOrDefault("Class", 0L);
        long edgeTotal = edges.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("  files: %d | classes: %d | methods: %d%n", files, classes, methods);
        System.out.printf("  edges: %d total across %d types%n", edgeTotal, edges.size());
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void print(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        TableRenderer.render(System.out, rows);
    }
}
