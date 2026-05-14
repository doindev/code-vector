package io.doindev.cvector.cli;

import io.doindev.cvector.cli.commands.AuditCommand;
import io.doindev.cvector.cli.commands.BadgeCommand;
import io.doindev.cvector.cli.commands.ChangelogCommand;
import io.doindev.cvector.cli.commands.CiCommand;
import io.doindev.cvector.cli.commands.CommunitiesCommand;
import io.doindev.cvector.cli.commands.DashboardCommand;
import io.doindev.cvector.cli.commands.DbCommand;
import io.doindev.cvector.cli.commands.DiffCommand;
import io.doindev.cvector.cli.commands.DoctorCommand;
import io.doindev.cvector.cli.commands.DuplicatesCommand;
import io.doindev.cvector.cli.commands.HostCommand;
import io.doindev.cvector.cli.commands.McpCommand;
import io.doindev.cvector.cli.commands.MigrateCommand;
import io.doindev.cvector.cli.commands.ExplainCommand;
import io.doindev.cvector.cli.commands.FlowsCommand;
import io.doindev.cvector.cli.commands.GuardCommand;
import io.doindev.cvector.cli.commands.ImpactCommand;
import io.doindev.cvector.cli.commands.InitCommand;
import io.doindev.cvector.cli.commands.OnboardCommand;
import io.doindev.cvector.cli.commands.PrImpactCommand;
import io.doindev.cvector.cli.commands.ProjectCommand;
import io.doindev.cvector.cli.commands.QueryCommand;
import io.doindev.cvector.cli.commands.RecentCommand;
import io.doindev.cvector.cli.commands.RenameCommand;
import io.doindev.cvector.cli.commands.RulesCommand;
import io.doindev.cvector.cli.commands.ScanCommand;
import io.doindev.cvector.cli.commands.ScanIncrementalCommand;
import io.doindev.cvector.cli.commands.SearchCommand;
import io.doindev.cvector.cli.commands.ServeCommand;
import io.doindev.cvector.cli.commands.ServiceLinksCommand;
import io.doindev.cvector.cli.commands.StatusCommand;
import io.doindev.cvector.cli.commands.TestImpactCommand;
import io.doindev.cvector.cli.commands.StopCommand;
import io.doindev.cvector.cli.commands.WatchCommand;
import io.doindev.cvector.cli.commands.WikiCommand;
import io.doindev.cvector.cli.commands.embedded.EmbeddedCommand;
import io.doindev.cvector.cli.commands.setup.SetupAntigravityCommand;
import io.doindev.cvector.cli.commands.setup.SetupClaudeCommand;
import io.doindev.cvector.cli.commands.setup.SetupCursorCommand;
import io.doindev.cvector.cli.commands.setup.SetupWindsurfCommand;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Component
@Command(
        name = "cvector",
        mixinStandardHelpOptions = true,
        version = "cvector 0.0.1",
        description = "Neo4j-backed code knowledge graph.",
        subcommands = {
                InitCommand.class,
                ScanCommand.class,
                ScanIncrementalCommand.class,
                WatchCommand.class,
                StatusCommand.class,
                DoctorCommand.class,
                QueryCommand.class,
                SearchCommand.class,
                ExplainCommand.class,
                ImpactCommand.class,
                TestImpactCommand.class,
                RenameCommand.class,
                PrImpactCommand.class,
                MigrateCommand.class,
                RecentCommand.class,
                ProjectCommand.class,
                McpCommand.class,
                DbCommand.class,
                HostCommand.class,
                OnboardCommand.class,
                WikiCommand.class,
                ChangelogCommand.class,
                BadgeCommand.class,
                RulesCommand.class,
                GuardCommand.class,
                AuditCommand.class,
                CommunitiesCommand.class,
                DuplicatesCommand.class,
                FlowsCommand.class,
                ServiceLinksCommand.class,
                DiffCommand.class,
                CiCommand.class,
                DashboardCommand.class,
                ServeCommand.class,
                StopCommand.class,
                SetupClaudeCommand.class,
                SetupCursorCommand.class,
                SetupWindsurfCommand.class,
                SetupAntigravityCommand.class,
                EmbeddedCommand.class,
                HelpCommand.class
        }
)
public class CvectorCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    /**
     * Legacy root-level {@code --embedded} flag. Kept for back-compat with the previous boot
     * script that ran {@code java -jar cvector.jar --embedded dashboard}. Not inherited into
     * subcommands so it doesn't collide with {@code cvector db --embedded}. New invocations
     * should use {@code --backend embedded} or the persistent {@code cvector db --embedded}.
     */
    @Option(names = "--embedded", negatable = true,
            description = "(legacy) One-shot override: force the embedded KuzuDB backend. "
                    + "Place before the subcommand name. Prefer `--backend embedded` going forward.")
    public void setEmbedded(boolean embedded) {
        if (embedded) System.setProperty("cvector.embedded", "true");
    }

    /**
     * One-shot backend override via {@code --backend <embedded|remote|docker>}. Distinct from
     * the {@code cvector db --embedded|--remote|--docker} subcommand (which persists the choice
     * to settings.json) — this flag affects only the current invocation. Use a long-form name
     * with a value so the {@code --remote} / {@code --docker} sub-flags on {@code cvector db}
     * don't collide with inherited root flags.
     */
    @Option(names = "--backend", scope = ScopeType.INHERIT,
            description = "One-shot backend override (embedded | remote | docker) — overrides settings.json for this invocation.")
    public void setBackend(String backend) {
        if (backend != null && !backend.isBlank()) {
            System.setProperty("cvector.backend", backend.trim().toLowerCase());
        }
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }
}
