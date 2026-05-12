package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigLoader;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Violation;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "rules", description = "Evaluate architecture rules against the active project's graph.")
public class RulesCommand implements Callable<Integer> {

    @Option(names = "--init", description = "Write a template .cvector/rules.yml in the active project.")
    private boolean init;

    @Option(names = "--json", description = "Output JSON instead of a table.")
    private boolean json;

    private final CvectorRuntime runtime;

    public RulesCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws IOException {
        Path configRoot = runtime.resolveConfigRoot();
        Path rulesPath = configRoot.resolve(".cvector").resolve("rules.yml");

        if (init) {
            if (Files.exists(rulesPath)) {
                System.err.println("already exists: " + rulesPath);
                return 1;
            }
            Files.writeString(rulesPath, RulesConfigLoader.defaultYaml());
            System.out.println("wrote " + rulesPath);
            return 0;
        }

        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        RulesConfig rulesCfg = RulesConfigLoader.loadOrDefault(rulesPath);

        try (GraphStore store = runtime.openGraphStore(cfg)) {
            RulesEngine engine = new RulesEngine(active.projectId(), store, rulesCfg);
            RulesEngine.Report report = engine.run();
            if (json) {
                System.out.println(reportToJson(report));
                return report.hasErrors() ? 0 : 0;
            }
            renderHuman(report);
            return 0;
        }
    }

    private static void renderHuman(RulesEngine.Report report) {
        System.out.println("rules report for project " + report.projectId());
        System.out.println();
        for (RulesEngine.RuleRun run : report.runs()) {
            System.out.printf("[%s] %-20s %d violation(s)%n", run.severity(), run.rule(), run.violations());
            if (run.violations() == 0) continue;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Violation v : run.findings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subject", v.subject());
                row.put("line", v.line() == null ? "" : v.line());
                row.put("message", v.message());
                rows.add(row);
            }
            TableRenderer.render(System.out, rows);
            System.out.println();
        }
        Map<String, Integer> bySev = report.bySeverity();
        System.out.println("totals: " + bySev + " (overall: " + report.totalViolations() + ")");
    }

    static String reportToJson(RulesEngine.Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"projectId\":\"").append(esc(report.projectId())).append("\",\"runs\":[");
        boolean firstRun = true;
        for (RulesEngine.RuleRun r : report.runs()) {
            if (!firstRun) sb.append(",");
            firstRun = false;
            sb.append("{\"rule\":\"").append(esc(r.rule())).append("\",")
                    .append("\"severity\":\"").append(r.severity()).append("\",")
                    .append("\"violations\":").append(r.violations()).append(",")
                    .append("\"findings\":[");
            boolean firstFinding = true;
            for (Violation v : r.findings()) {
                if (!firstFinding) sb.append(",");
                firstFinding = false;
                sb.append("{")
                        .append("\"subject\":\"").append(esc(v.subject())).append("\",")
                        .append("\"message\":\"").append(esc(v.message())).append("\",")
                        .append("\"line\":").append(v.line() == null ? "null" : v.line()).append(",")
                        .append("\"fileId\":\"").append(esc(v.fileId() == null ? "" : v.fileId())).append("\"}");
            }
            sb.append("]}");
        }
        sb.append("],\"totals\":").append(report.bySeverity().toString().replace("=", ":"));
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
