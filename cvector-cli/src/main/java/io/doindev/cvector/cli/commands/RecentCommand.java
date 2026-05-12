package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "recent", description = "Show recently-ingested graph nodes.")
public class RecentCommand implements Callable<Integer> {

    @Option(names = "--since", description = "Time window (e.g. 24h, 7d, 30m, 1d). Default: 24h.")
    private String since = "24h";

    @Option(names = "--limit", description = "Max rows (default 20).")
    private int limit = 20;

    private final CvectorRuntime runtime;

    public RecentCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        Duration window = parseDuration(since);
        ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).minus(window);

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            String cypher = "MATCH (n) WHERE n.projectId = $pid AND n.lastIngestedAt > datetime($cutoff) "
                    + "RETURN labels(n)[0] AS label, n.fqName AS fqName, n.name AS name, "
                    + "toString(n.lastIngestedAt) AS lastIngestedAt "
                    + "ORDER BY n.lastIngestedAt DESC LIMIT $lim";
            Map<String, Object> params = new HashMap<>();
            params.put("pid", active.projectId());
            params.put("cutoff", cutoff.toString());
            params.put("lim", limit);
            List<Map<String, Object>> rows = q.raw(cypher, params);
            if (rows.isEmpty()) {
                System.out.println("(no nodes touched in last " + since + ")");
                return 0;
            }
            TableRenderer.render(System.out, rows);
        }
        return 0;
    }

    static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ofHours(24);
        int n = 0;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            n = n * 10 + (s.charAt(i) - '0');
            i++;
        }
        if (i == 0) throw new IllegalArgumentException("invalid --since value: " + s);
        String unit = s.substring(i).toLowerCase();
        return switch (unit) {
            case "s", "sec", "secs" -> Duration.ofSeconds(n);
            case "m", "min", "mins" -> Duration.ofMinutes(n);
            case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(n);
            case "d", "day", "days" -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("unknown time unit: " + unit);
        };
    }
}
