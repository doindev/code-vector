package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.commands.projects.ProjectCreateCommand;
import io.doindev.cvector.cli.commands.projects.ProjectInfoCommand;
import io.doindev.cvector.cli.commands.projects.ProjectListCommand;
import io.doindev.cvector.cli.commands.projects.ProjectSwitchCommand;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Component
@Command(
        name = "project",
        description = "Manage projects in the cvector workspace.",
        subcommands = {
                ProjectListCommand.class,
                ProjectCreateCommand.class,
                ProjectSwitchCommand.class,
                ProjectInfoCommand.class
        }
)
public class ProjectCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }
}
