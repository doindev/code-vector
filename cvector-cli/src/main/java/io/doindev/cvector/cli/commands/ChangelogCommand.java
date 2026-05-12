package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "changelog", description = "Auto-generate a changelog from recent graph changes (uses lastIngestedAt).")
public class ChangelogCommand implements Callable<Integer> {

    @Option(names = "--since", description = "Time window: 24h, 7d, 30m, etc. (default 7d).")
    private String since = "7d";

    @Option(names = "--markdown", description = "Emit markdown instead of a table (default markdown).")
    private boolean markdown = true;

    private final CvectorRuntime runtime;

    public ChangelogCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        Duration window = RecentCommand.parseDuration(since);
        ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).minus(window);

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            String cutoffStr = cutoff.toString();
            List<Map<String, Object>> all = q.raw(
                    "MATCH (n) WHERE n.projectId = $pid AND n.lastIngestedAt > datetime($cutoff) "
                            + "RETURN labels(n)[0] AS label, n.fqName AS fqName, "
                            + "toString(n.lastIngestedAt) AS lastIngestedAt LIMIT 5000",
                    Map.of("pid", active.projectId(), "cutoff", cutoffStr)
            );

            Map<String, List<Map<String, Object>>> byLabel = new LinkedHashMap<>();
            for (Map<String, Object> row : all) {
                String label = String.valueOf(row.get("label"));
                byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(row);
            }

            StringBuilder s = new StringBuilder();
            s.append("# Changelog — last ").append(since).append("\n\n");
            s.append("_Project: ").append(active.name()).append("_  \n");
            s.append("_Cutoff: ").append(cutoffStr).append("_  \n");
            s.append("_Touched nodes: ").append(all.size()).append("_\n\n");
            if (all.isEmpty()) {
                s.append("No graph nodes touched within the window.\n");
            } else {
                for (Map.Entry<String, List<Map<String, Object>>> e : byLabel.entrySet()) {
                    s.append("## ").append(e.getKey())
                            .append(" (").append(e.getValue().size()).append(")\n\n");
                    int shown = 0;
                    for (Map<String, Object> r : e.getValue()) {
                        if (shown++ >= 50) {
                            s.append("- _(... ").append(e.getValue().size() - shown + 1).append(" more)_\n");
                            break;
                        }
                        s.append("- `").append(r.get("fqName")).append("` ")
                                .append("(touched ").append(r.get("lastIngestedAt")).append(")\n");
                    }
                    s.append("\n");
                }
            }
            System.out.print(s);
        }
        return 0;
    }
}
