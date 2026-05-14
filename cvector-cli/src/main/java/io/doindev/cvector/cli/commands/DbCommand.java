package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cvector db --embedded | --remote | --docker} — flip the backend mode in settings.json.
 * Invoked with no flag prints the current backend. {@code --remote} requires a populated
 * {@code neo4j} block; {@code --docker} ensures the {@code docker} block exists.
 */
@Component
@Command(name = "db", description = "Show or switch the cvector graph backend (embedded / remote / docker).",
        mixinStandardHelpOptions = true)
public class DbCommand implements Callable<Integer> {

    @ArgGroup(exclusive = true)
    private Mode mode;

    static class Mode {
        @Option(names = "--embedded", description = "Use the bundled KuzuDB store (default for new projects).")
        boolean embedded;
        @Option(names = "--remote", description = "Use the Neo4j connection from settings.json.")
        boolean remote;
        @Option(names = "--docker", description = "Use a docker-managed Neo4j container (requires Docker on the host).")
        boolean docker;
    }

    private final CvectorRuntime runtime;

    public DbCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path root = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        if (mode == null || (!mode.embedded && !mode.remote && !mode.docker)) {
            CvectorConfig cfg = svc.load(root);
            System.out.println("backend: " + cfg.backendOrDefault());
            return 0;
        }
        String target;
        if (mode.embedded) target = CvectorConfig.BACKEND_EMBEDDED;
        else if (mode.remote) target = CvectorConfig.BACKEND_REMOTE;
        else target = CvectorConfig.BACKEND_DOCKER;

        CvectorConfig before = svc.load(root);
        if (CvectorConfig.BACKEND_REMOTE.equals(target)) {
            CvectorConfig.Neo4jConfig n = before.neo4j();
            if (n == null || n.uri() == null || n.uri().isBlank()) {
                System.err.println("refusing to switch to remote: neo4j block is missing or empty in "
                        + svc.configPath(root) + ". Add a neo4j section with uri/user/password first.");
                return 2;
            }
        }
        CvectorConfig.DockerConfig dockerCfg = before.docker() != null
                ? before.docker()
                : CvectorConfig.DockerConfig.defaults();
        CvectorConfig updated = svc.update(root, cfg -> new CvectorConfig(
                cfg.activeProject(), cfg.projects(), cfg.neo4j(),
                target, cfg.rest(), cfg.mcp(), dockerCfg, cfg.rules(), cfg.kuzu()));
        System.out.println("backend switched to '" + updated.backendOrDefault() + "'");
        if (CvectorConfig.BACKEND_DOCKER.equals(target)) {
            CvectorConfig.DockerConfig d = updated.dockerOrDefault();
            System.out.println("  image:        " + d.image() + ":" + d.neo4jVersion());
            System.out.println("  container:    " + d.containerName());
            System.out.println("  bolt port:    " + d.boltPort());
            System.out.println("  http port:    " + d.httpPort());
            System.out.println();
            System.out.println("Next: `cvector serve` / `cvector dashboard` will start the container "
                    + "if it isn't already running.");
        } else if (CvectorConfig.BACKEND_REMOTE.equals(target)) {
            System.out.println("  neo4j uri: " + updated.neo4j().uri());
        }
        System.out.println();
        System.out.println("Restart the dashboard / serve process to pick up the new backend.");
        return 0;
    }
}
