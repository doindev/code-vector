package io.doindev.cvector.cli.commands.projects;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "switch", description = "Switch the active project.", mixinStandardHelpOptions = true)
public class ProjectSwitchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String name;

    private final CvectorRuntime runtime;

    public ProjectSwitchCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path configRoot = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        CvectorConfig cfg = svc.load(configRoot);
        if (!cfg.projects().containsKey(name)) {
            System.err.println("project '" + name + "' not found. Available: " + cfg.projects().keySet());
            return 1;
        }
        CvectorConfig updated = new CvectorConfig(name, cfg.projects(), cfg.neo4j(),
                cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker());
        svc.save(configRoot, updated);
        System.out.println("active project switched to '" + name + "'");
        return 0;
    }
}
