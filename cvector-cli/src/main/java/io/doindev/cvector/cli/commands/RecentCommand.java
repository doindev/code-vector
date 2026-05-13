package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "recent", description = "Show recently-ingested graph nodes.", mixinStandardHelpOptions = true)
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
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            List<Map<String, Object>> rows = store.recentlyChanged(active.projectId(), window, limit);
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
