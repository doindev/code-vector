package io.doindev.cvector.cli.commands.projects;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@Command(name = "create", description = "Create a new project in the workspace.", mixinStandardHelpOptions = true)
public class ProjectCreateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String name;

    @Option(names = "--root", description = "Project root path (defaults to current directory).")
    private Path root;

    @Option(names = "--switch", description = "Also switch the active project to the new one.")
    private boolean switchAfter;

    private final CvectorRuntime runtime;

    public ProjectCreateCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path configRoot = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        CvectorConfig cfg = svc.load(configRoot);

        if (cfg.projects().containsKey(name)) {
            System.err.println("project '" + name + "' already exists");
            return 1;
        }
        Path rootPath = root != null ? root.toAbsolutePath().normalize() : Paths.get(".").toAbsolutePath().normalize();
        Map<String, CvectorConfig.ProjectEntry> projects = new LinkedHashMap<>(cfg.projects());
        String projectId = UUID.randomUUID().toString();
        projects.put(name, new CvectorConfig.ProjectEntry(projectId, name, rootPath.toString()));

        String activeProject = switchAfter ? name : cfg.activeProject();
        CvectorConfig updated = new CvectorConfig(activeProject, projects, cfg.neo4j(),
                cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker());
        svc.save(configRoot, updated);

        System.out.println("created project '" + name + "' (" + projectId + ") at " + rootPath);
        if (switchAfter) System.out.println("active project is now '" + name + "'");
        return 0;
    }
}
