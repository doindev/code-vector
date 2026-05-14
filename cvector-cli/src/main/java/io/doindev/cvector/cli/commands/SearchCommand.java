package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "search", description = "Search graph nodes by name (substring or wildcard).", mixinStandardHelpOptions = true)
public class SearchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Query (substring; supports * wildcards).")
    private String query;

    @Option(names = "--limit", description = "Max results (default 50).")
    private int limit = 50;

    @Option(names = "--label", description = "Restrict to one label (Class, Method, Table, ApiEndpoint, ...).")
    private String label;

    private final CvectorRuntime runtime;

    public SearchCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            List<Map<String, Object>> rows = store.searchByName(active.projectId(), query, label, limit);
            if (rows.isEmpty()) {
                System.out.println("(no results for '" + query + "')");
                return 0;
            }
            TableRenderer.render(System.out, rows);
            System.out.println();
            System.out.println("total: " + rows.size());
        }
        return 0;
    }
}
