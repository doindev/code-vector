package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code cvector duplicates [--min N]} — surface groups of Method nodes that look like
 * duplicates (same name + paramCount + returnType + similar body size). Surfaces refactor
 * candidates without needing source-text comparison.
 */
@Component
@Command(name = "duplicates",
        description = "Find groups of methods that look like duplicates (same shape + size).",
        mixinStandardHelpOptions = true)
public class DuplicatesCommand implements Callable<Integer> {

    @Option(names = "--min", description = "Minimum group size to report (default 2).")
    private int min = 2;

    private final CvectorRuntime runtime;

    public DuplicatesCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            List<Map<String, Object>> groups = store.findDuplicates(active.projectId(), Math.max(2, min));
            if (groups.isEmpty()) {
                System.out.println("no duplicate-shape method groups found (min=" + min + ")");
                return 0;
            }
            System.out.println("duplicate-shape method groups (min=" + min + "):");
            System.out.println();
            for (Map<String, Object> g : groups) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", g.get("name"));
                row.put("params", g.get("paramCount"));
                row.put("returns", g.get("returnType"));
                row.put("~lines", g.get("lineBucket"));
                row.put("count", g.get("occurrences"));
                TableRenderer.render(System.out, List.of(row));
                Object members = g.get("members");
                if (members instanceof List<?> list) {
                    int shown = 0;
                    for (Object m : list) {
                        if (shown++ >= 10) {
                            System.out.println("    ... (" + (list.size() - shown + 1) + " more)");
                            break;
                        }
                        System.out.println("    - " + m);
                    }
                }
                System.out.println();
            }
            System.out.println("total groups: " + groups.size());
            return 0;
        }
    }
}
