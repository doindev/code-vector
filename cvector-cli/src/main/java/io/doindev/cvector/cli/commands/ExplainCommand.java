package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "explain", description = "Show full context for a symbol: type, file, callers, callees.")
public class ExplainCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Symbol name (fully qualified or last segment).")
    private String symbol;

    private final CvectorRuntime runtime;

    public ExplainCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            List<Map<String, Object>> matches = q.findSymbol(active.projectId(), symbol);
            if (matches.isEmpty()) {
                System.err.println("no symbol found matching '" + symbol + "'");
                return 1;
            }
            if (matches.size() > 1) {
                System.out.println("multiple matches:");
                TableRenderer.render(System.out, matches);
                System.out.println();
                System.out.println("(using first match)");
            }
            Map<String, Object> hit = matches.get(0);
            String id = (String) hit.get("id");
            String label = (String) hit.get("label");
            String fqName = (String) hit.get("fqName");

            System.out.println("symbol:   " + fqName);
            System.out.println("kind:     " + label);
            Map<String, Object> fileMap = hit.get("fileId") != null ? q.fileOf(active.projectId(), (String) hit.get("fileId")) : Map.of();
            if (!fileMap.isEmpty()) {
                System.out.println("file:     " + fileMap.get("path") + ":" + hit.getOrDefault("startLine", "?"));
            }
            System.out.println();

            if ("Method".equals(label)) {
                System.out.println("callers (" + label + " <- CALLS):");
                List<Map<String, Object>> callers = q.callers(active.projectId(), id);
                if (callers.isEmpty()) System.out.println("  (none)");
                else TableRenderer.render(System.out, callers);
                System.out.println();
                System.out.println("callees (" + label + " -> CALLS):");
                List<Map<String, Object>> callees = q.callees(active.projectId(), id);
                if (callees.isEmpty()) System.out.println("  (none)");
                else TableRenderer.render(System.out, callees);
            }
        }
        return 0;
    }
}
