package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "impact", description = "Show downstream impact of changing a symbol.")
public class ImpactCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Symbol name (fully qualified or last segment).")
    private String symbol;

    @Option(names = "--depth", description = "Max traversal depth (default 3).")
    private int depth = 3;

    private final CvectorRuntime runtime;

    public ImpactCommand(CvectorRuntime runtime) {
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
            Map<String, Object> hit = matches.get(0);
            String id = (String) hit.get("id");
            String fqName = (String) hit.get("fqName");

            System.out.println("impact of changing: " + fqName + " (depth=" + depth + ")");
            List<Map<String, Object>> impacted = q.impactDownstream(active.projectId(), id, depth);
            if (impacted.isEmpty()) {
                System.out.println("  (no downstream impact found)");
            } else {
                TableRenderer.render(System.out, impacted);
                System.out.println();
                System.out.println("total affected: " + impacted.size());
            }
        }
        return 0;
    }
}
