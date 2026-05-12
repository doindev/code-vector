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
@Command(name = "search", description = "Search graph nodes by name (substring or wildcard).")
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
        String regex = "(?i).*" + query.replace("*", ".*") + ".*";
        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            String labelFilter = label != null ? "AND any(l IN labels(n) WHERE l = $label) " : "";
            String cypher = "MATCH (n) WHERE n.projectId = $pid "
                    + "AND (n.fqName =~ $regex OR n.name =~ $regex) " + labelFilter
                    + "RETURN labels(n)[0] AS label, n.fqName AS fqName, n.name AS name, n.id AS id LIMIT $lim";
            List<Map<String, Object>> rows = q.raw(cypher,
                    Map.of("pid", active.projectId(), "regex", regex, "label", label == null ? "" : label, "lim", limit));
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
