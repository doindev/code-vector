package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.commands.mcp.McpInfoCommand;
import io.doindev.cvector.cli.commands.mcp.McpUpdateCommand;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Top-level {@code cvector mcp} command. Bare invocation prints the same info as
 * {@code cvector mcp info}, matching the user-facing UX request that {@code cvector mcp} (no
 * subcommand) shows the current connection details.
 */
@Component
@Command(
        name = "mcp",
        description = "Show or update MCP server connection info.",
        mixinStandardHelpOptions = true,
        subcommands = {
                McpInfoCommand.class,
                McpUpdateCommand.class
        }
)
public class McpCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public McpCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.McpConfig mcp = cfg.mcpOrDefault();
        CvectorConfig.RestConfig rest = cfg.restOrDefault();
        System.out.println("MCP configuration:");
        System.out.println("  transport: " + mcp.transport());
        System.out.println("  url:       " + mcp.url());
        System.out.println("  host:      " + rest.host());
        System.out.println("  port:      " + rest.port());
        System.out.println();
        System.out.println("Subcommands: `cvector mcp info`, `cvector mcp update --transport <T> <URL>`");
        return 0;
    }
}
