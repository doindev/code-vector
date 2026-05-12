package io.doindev.cvector.cli.commands.projects;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "list", description = "List all projects in the workspace.")
public class ProjectListCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public ProjectListCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        if (cfg.projects().isEmpty()) {
            System.out.println("(no projects)");
            return 0;
        }
        cfg.projects().forEach((name, entry) -> {
            String marker = name.equals(cfg.activeProject()) ? "* " : "  ";
            System.out.printf("%s%-24s %s  %s%n", marker, name, entry.projectId(), entry.rootPath());
        });
        return 0;
    }
}
