package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "status", description = "Show project graph statistics.")
public class StatusCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public StatusCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            if (!client.ping()) {
                System.err.println("neo4j unreachable at " + client.uri());
                return 1;
            }
            GraphQueries q = new GraphQueries(client);
            System.out.println("project:  " + active.name() + " (" + active.projectId() + ")");
            System.out.println("root:     " + active.rootPath());
            System.out.println("neo4j:    " + client.uri());
            System.out.println();
            System.out.println("nodes:");
            Map<String, Long> nodes = q.nodeCounts(active.projectId());
            if (nodes.isEmpty()) System.out.println("  (none)");
            nodes.forEach((label, c) -> System.out.printf("  %-12s %d%n", label, c));
            System.out.println();
            System.out.println("edges:");
            Map<String, Long> edges = q.edgeCounts(active.projectId());
            if (edges.isEmpty()) System.out.println("  (none)");
            edges.forEach((type, c) -> System.out.printf("  %-12s %d%n", type, c));
        }
        return 0;
    }
}
