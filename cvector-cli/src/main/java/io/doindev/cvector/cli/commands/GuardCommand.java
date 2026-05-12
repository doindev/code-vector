package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigLoader;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Severity;
import io.doindev.cvector.rules.Violation;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.Callable;

@Component
@Command(name = "guard", description = "Quality gate: run rules and exit non-zero on violations (CI mode).")
public class GuardCommand implements Callable<Integer> {

    @Option(names = "--ci", description = "Exit code 1 on any ERROR-severity violation.")
    private boolean ci;

    @Option(names = "--json", description = "Emit JSON instead of a table.")
    private boolean json;

    @Option(names = "--god-file-threshold", description = "Override godFileMethods threshold.")
    private Integer godFileThreshold;

    @Option(names = "--god-class-threshold", description = "Override godClassMethods threshold.")
    private Integer godClassThreshold;

    @Option(names = "--long-method-threshold", description = "Override longMethodLines threshold.")
    private Integer longMethodThreshold;

    @Option(names = "--install-hook", description = "Install .git/hooks/pre-commit invoking `cvector guard --ci`.")
    private boolean installHook;

    @Option(names = "--uninstall-hook", description = "Remove the cvector pre-commit hook.")
    private boolean uninstallHook;

    @Option(names = "--fail-on-warn", description = "Treat WARN as failing too.")
    private boolean failOnWarn;

    private final CvectorRuntime runtime;

    public GuardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    public Integer callForCi(boolean failOnWarnOverride) throws IOException {
        this.ci = true;
        if (failOnWarnOverride) this.failOnWarn = true;
        return call();
    }

    @Override
    public Integer call() throws IOException {
        Path configRoot = runtime.resolveConfigRoot();

        if (installHook) return installHook(configRoot);
        if (uninstallHook) return uninstallHook(configRoot);

        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        RulesConfig rulesCfg = RulesConfigLoader.loadOrDefault(configRoot.resolve(".cvector").resolve("rules.yml"));
        applyOverrides(rulesCfg);

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            RulesEngine engine = new RulesEngine(active.projectId(), new GraphQueries(client), rulesCfg);
            RulesEngine.Report report = engine.run();

            if (json) {
                System.out.println(RulesCommand.reportToJson(report));
            } else {
                renderSummary(report);
            }

            boolean fail = report.hasErrors() || (failOnWarn && report.runs().stream().anyMatch(r -> r.severity() == Severity.WARN && r.violations() > 0));
            if (fail && ci) return 1;
            return 0;
        }
    }

    private void applyOverrides(RulesConfig cfg) {
        if (godFileThreshold != null) cfg.getThresholds().put("godFileMethods", godFileThreshold);
        if (godClassThreshold != null) cfg.getThresholds().put("godClassMethods", godClassThreshold);
        if (longMethodThreshold != null) cfg.getThresholds().put("longMethodLines", longMethodThreshold);
    }

    private static void renderSummary(RulesEngine.Report report) {
        boolean any = false;
        for (RulesEngine.RuleRun run : report.runs()) {
            if (run.violations() == 0) continue;
            any = true;
            String marker = run.severity() == Severity.ERROR ? "X" : "!";
            System.out.printf("[%s] %s: %d%n", marker, run.rule(), run.violations());
            int shown = 0;
            for (Violation v : run.findings()) {
                if (shown++ >= 5) {
                    System.out.printf("    ... %d more%n", run.violations() - shown + 1);
                    break;
                }
                System.out.printf("    - %s%s — %s%n",
                        v.subject(),
                        v.line() == null ? "" : ":" + v.line(),
                        v.message());
            }
        }
        if (!any) System.out.println("guard: clean (no violations).");
        System.out.println();
        System.out.println("totals: " + report.bySeverity() + " (overall: " + report.totalViolations() + ")");
    }

    private int installHook(Path configRoot) throws IOException {
        Path hook = configRoot.resolve(".git").resolve("hooks").resolve("pre-commit");
        if (!Files.exists(hook.getParent())) {
            System.err.println("not a git repo: " + configRoot);
            return 2;
        }
        String script = """
                #!/bin/sh
                # installed by `cvector guard --install-hook`
                exec cvector guard --ci
                """;
        Files.writeString(hook, script);
        try {
            Files.setPosixFilePermissions(hook, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
        }
        System.out.println("installed pre-commit hook: " + hook);
        return 0;
    }

    private int uninstallHook(Path configRoot) throws IOException {
        Path hook = configRoot.resolve(".git").resolve("hooks").resolve("pre-commit");
        if (!Files.exists(hook)) {
            System.out.println("no pre-commit hook to remove at " + hook);
            return 0;
        }
        String content = Files.readString(hook);
        if (!content.contains("cvector guard")) {
            System.err.println("pre-commit hook at " + hook + " does not look cvector-installed; not removing");
            return 1;
        }
        Files.delete(hook);
        System.out.println("removed pre-commit hook: " + hook);
        return 0;
    }
}
