package io.doindev.cvector.cli.commands.projects;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "info", description = "Show the active project's configuration.")
public class ProjectInfoCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public ProjectInfoCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        System.out.println("active project: " + active.name());
        System.out.println("  id:       " + active.projectId());
        System.out.println("  root:     " + active.rootPath());
        System.out.println("  config:   " + runtime.configService().configPath(runtime.resolveConfigRoot()));
        if (cfg.neo4j() != null) {
            System.out.println("  neo4j:    " + cfg.neo4j().uri() + " (user=" + cfg.neo4j().user() + ")");
        }
        return 0;
    }
}
