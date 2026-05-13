package io.doindev.cvector.cli.commands.setup;

import io.doindev.cvector.cli.CvectorRuntime;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "setup-cursor", description = "Register cvector as an MCP server in .cursor/mcp.json for this project.", mixinStandardHelpOptions = true)
public class SetupCursorCommand implements Callable<Integer> {

    @Option(names = "--name", description = "Server entry name (default 'cvector').")
    private String name = "cvector";

    private final CvectorRuntime runtime;

    public SetupCursorCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path projectRoot = runtime.resolveConfigRoot();
        Path cfg = IdeSetupUtil.cursorConfigPath(projectRoot);
        Path jar = IdeSetupUtil.detectCvectorJar();
        IdeSetupUtil.writeMcpServerEntry(cfg, name, projectRoot, jar, runtime);
        System.out.println("registered cvector MCP server for Cursor in this project:");
        System.out.println("  config:  " + cfg);
        System.out.println("  cwd:     " + projectRoot);
        System.out.println("  jar:     " + jar);
        return 0;
    }
}
