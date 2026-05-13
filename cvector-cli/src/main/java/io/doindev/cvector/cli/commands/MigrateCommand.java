package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * {@code cvector migrate <symbol> [--to <new>] [--json]} — generates a structured migration
 * plan for replacing or refactoring a symbol. Composes existing graph queries (find, callers,
 * references, importers, test reach) into a sequenced plan with a risk score so a user can
 * size a migration before starting it.
 *
 * <p>This is the action-oriented counterpart to the MCP {@code cvector-migration-plan} prompt
 * — the prompt asks an agent to fetch + synthesise these data; this command computes the
 * same data directly and emits it as a checklist.
 */
@Component
@Command(name = "migrate",
        description = "Guided migration plan for a symbol — inventory, sequencing, risk score.",
        mixinStandardHelpOptions = true)
public class MigrateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Symbol being migrated away from (fully-qualified or last segment).")
    private String symbol;

    @Option(names = "--to", description = "Optional replacement symbol or library — included in the plan summary.")
    private String to;

    @Option(names = "--depth", description = "Max test-reach depth (default 5).")
    private int depth = 5;

    @Option(names = "--json", description = "Emit JSON instead of a human-readable plan.")
    private boolean json;

    private final CvectorRuntime runtime;

    public MigrateCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            List<Map<String, Object>> hits = store.findSymbol(active.projectId(), symbol);
            if (hits.isEmpty()) {
                System.err.println("no symbol found matching '" + symbol + "'");
                return 1;
            }
            Map<String, Object> hit = hits.get(0);
            MigrationPlan plan = buildPlan(store, active.projectId(), hit);
            if (json) {
                System.out.println(plan.toJson());
            } else {
                plan.renderHuman(System.out, to);
            }
            return 0;
        }
    }

    /** Compose the per-section data and the risk score into a single struct so render + JSON share. */
    private MigrationPlan buildPlan(GraphStore store, String pid, Map<String, Object> hit) {
        String id = (String) hit.get("id");
        String fqName = String.valueOf(hit.get("fqName"));
        String label = String.valueOf(hit.getOrDefault("label", ""));
        Map<String, Object> file = hit.get("fileId") != null
                ? store.fileOf(pid, (String) hit.get("fileId"))
                : Map.of();

        List<Map<String, Object>> callers = store.callers(pid, id);
        List<Map<String, Object>> references = store.referencingNodes(pid, id, 500);
        List<Map<String, Object>> importers = store.importingFiles(pid, id, 200);
        List<Map<String, Object>> tests = store.testReach(pid, id, Math.max(1, Math.min(depth, 12)));

        // Group callers by module (first 4 dotted segments) so the inventory is bucketable.
        Map<String, Integer> byModule = new TreeMap<>();
        for (Map<String, Object> c : callers) {
            String cfn = String.valueOf(c.get("fqName"));
            byModule.merge(modulePrefix(cfn), 1, Integer::sum);
        }

        int risk = riskScore(callers.size(), tests.size(), references.size());
        return new MigrationPlan(fqName, label, file, callers, references, importers, tests, byModule, risk);
    }

    /**
     * Risk score 0-100. Heuristic:
     * - callers contribute up to 60 points (10+ callers = full)
     * - references contribute up to 20 points (50+ refs = full)
     * - LACK of test coverage adds up to 20 points
     */
    private static int riskScore(int callers, int tests, int references) {
        int callPart = Math.min(60, callers * 6);
        int refPart = Math.min(20, references * 2 / 5);
        int testPart = tests > 0 ? 0 : 20;
        return Math.min(100, callPart + refPart + testPart);
    }

    /** First three dotted segments — coarse module bucket for the inventory table. */
    private static String modulePrefix(String fqName) {
        if (fqName == null) return "(unknown)";
        String[] parts = fqName.split("\\.");
        if (parts.length <= 3) return fqName;
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    /** Migration-plan payload shared by human + JSON renderers. */
    record MigrationPlan(String fqName, String label, Map<String, Object> file,
                         List<Map<String, Object>> callers, List<Map<String, Object>> references,
                         List<Map<String, Object>> importers, List<Map<String, Object>> tests,
                         Map<String, Integer> byModule, int risk) {

        void renderHuman(java.io.PrintStream out, String to) {
            out.println("migration plan for: " + fqName);
            out.println("kind:               " + label);
            if (file != null && !file.isEmpty()) {
                out.println("file:               " + file.getOrDefault("path", "(unknown)"));
            }
            if (to != null && !to.isBlank()) {
                out.println("replacement:        " + to);
            }
            out.println("risk score:         " + risk + " / 100"
                    + " (" + riskLabel(risk) + ")");
            out.println();

            out.println("== inventory by module ==");
            if (byModule.isEmpty()) {
                out.println("  (no callers — symbol is unused or only referenced via REFERENCES)");
            } else {
                for (Map.Entry<String, Integer> e : byModule.entrySet()) {
                    out.printf("  %-50s %d caller(s)%n", e.getKey(), e.getValue());
                }
            }
            out.println();

            out.println("== test coverage ==");
            if (tests.isEmpty()) {
                out.println("  WARNING: no tests reach this symbol — add coverage before migrating.");
            } else {
                int shown = 0;
                for (Map<String, Object> t : tests) {
                    if (shown++ >= 10) {
                        out.println("  ... (" + (tests.size() - shown + 1) + " more)");
                        break;
                    }
                    out.println("  - " + t.get("test") + "  (depth " + t.get("depth") + ")");
                }
            }
            out.println();

            out.println("== suggested sequencing ==");
            out.println("  1. Extract a thin wrapper or feature flag around " + fqName + ".");
            if (!tests.isEmpty()) {
                out.println("  2. Verify the " + tests.size() + " covering test(s) still pass against the wrapper.");
            } else {
                out.println("  2. Add tests covering at least one caller before swapping the impl.");
            }
            if (to != null && !to.isBlank()) {
                out.println("  3. Migrate callers module-by-module to " + to + ", largest module last.");
            } else {
                out.println("  3. Refactor callers module-by-module to the new shape, largest module last.");
            }
            out.println("  4. Once all callers migrated, delete the wrapper and the original symbol.");
            out.println();

            out.println("== blast radius ==");
            out.printf("  callers:    %d%n", callers.size());
            out.printf("  references: %d%n", references.size());
            out.printf("  importers:  %d%n", importers.size());
            out.printf("  modules:    %d%n", byModule.size());

            if (!callers.isEmpty()) {
                out.println();
                out.println("  top callers:");
                int shown = 0;
                for (Map<String, Object> c : callers) {
                    if (shown++ >= 10) { out.println("    ... (" + (callers.size() - shown + 1) + " more)"); break; }
                    out.println("    - " + c.get("fqName"));
                }
            }
        }

        String toJson() {
            // Minimal flat JSON — keeps the dep surface small (no Jackson here in cvector-cli).
            // The richer view is /api/migrate; this is for piping into shell scripts.
            StringBuilder sb = new StringBuilder();
            sb.append("{\"symbol\":\"").append(esc(fqName)).append("\",")
                    .append("\"label\":\"").append(esc(label)).append("\",")
                    .append("\"risk\":").append(risk).append(",")
                    .append("\"counts\":{")
                    .append("\"callers\":").append(callers.size()).append(",")
                    .append("\"references\":").append(references.size()).append(",")
                    .append("\"importers\":").append(importers.size()).append(",")
                    .append("\"tests\":").append(tests.size()).append(",")
                    .append("\"modules\":").append(byModule.size()).append("},");
            sb.append("\"byModule\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : byModule.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(esc(e.getKey())).append("\":").append(e.getValue());
            }
            sb.append("}}");
            return sb.toString();
        }

        private static String riskLabel(int score) {
            if (score >= 75) return "HIGH";
            if (score >= 40) return "MEDIUM";
            return "LOW";
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
