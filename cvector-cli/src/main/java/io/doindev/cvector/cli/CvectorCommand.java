package io.doindev.cvector.cli;

import io.doindev.cvector.cli.commands.AuditCommand;
import io.doindev.cvector.cli.commands.BadgeCommand;
import io.doindev.cvector.cli.commands.ChangelogCommand;
import io.doindev.cvector.cli.commands.CiCommand;
import io.doindev.cvector.cli.commands.CommunitiesCommand;
import io.doindev.cvector.cli.commands.DashboardCommand;
import io.doindev.cvector.cli.commands.DiffCommand;
import io.doindev.cvector.cli.commands.DoctorCommand;
import io.doindev.cvector.cli.commands.ExplainCommand;
import io.doindev.cvector.cli.commands.FlowsCommand;
import io.doindev.cvector.cli.commands.GuardCommand;
import io.doindev.cvector.cli.commands.ImpactCommand;
import io.doindev.cvector.cli.commands.InitCommand;
import io.doindev.cvector.cli.commands.OnboardCommand;
import io.doindev.cvector.cli.commands.ProjectCommand;
import io.doindev.cvector.cli.commands.QueryCommand;
import io.doindev.cvector.cli.commands.RecentCommand;
import io.doindev.cvector.cli.commands.RulesCommand;
import io.doindev.cvector.cli.commands.ScanCommand;
import io.doindev.cvector.cli.commands.ScanIncrementalCommand;
import io.doindev.cvector.cli.commands.SearchCommand;
import io.doindev.cvector.cli.commands.ServeCommand;
import io.doindev.cvector.cli.commands.ServiceLinksCommand;
import io.doindev.cvector.cli.commands.StatusCommand;
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
                RecentCommand.class,
                ProjectCommand.class,
                OnboardCommand.class,
                WikiCommand.class,
                ChangelogCommand.class,
                BadgeCommand.class,
                RulesCommand.class,
                GuardCommand.class,
                AuditCommand.class,
                CommunitiesCommand.class,
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

    @Option(names = "--embedded", scope = ScopeType.INHERIT, negatable = true,
            description = "Use the embedded KuzuDB store instead of project.json's neo4j block. "
                    + "Also enabled via CVECTOR_EMBEDDED=true.")
    public void setEmbedded(boolean embedded) {
        if (embedded) System.setProperty("cvector.embedded", "true");
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }
}
