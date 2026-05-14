package io.doindev.cvector.cli.commands.setup;

import io.doindev.cvector.cli.CvectorRuntime;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "setup-antigravity", description = "Register cvector as an MCP server in Google Antigravity's MCP config.", mixinStandardHelpOptions = true)
public class SetupAntigravityCommand implements Callable<Integer> {

    @Option(names = "--name", description = "Server entry name (default 'cvector').")
    private String name = "cvector";

    @Option(names = "--config", description = "Override the Antigravity config path.")
    private Path configPath;

    private final CvectorRuntime runtime;

    public SetupAntigravityCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path cfg = configPath != null ? configPath : IdeSetupUtil.antigravityConfigPath();
        Path projectRoot = runtime.resolveConfigRoot();
        Path jar = IdeSetupUtil.detectCvectorJar();
        IdeSetupUtil.writeMcpServerEntry(cfg, name, projectRoot, jar, runtime);
        System.out.println("registered cvector MCP server in Antigravity:");
        System.out.println("  config:  " + cfg);
        System.out.println("  cwd:     " + projectRoot);
        System.out.println("  jar:     " + jar);
        System.out.println("Restart Antigravity to pick up the new server.");
        return 0;
    }
}
