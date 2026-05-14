package io.doindev.cvector.cli.commands.mcp;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Default {@code cvector mcp} (no subcommand) — prints the MCP connection info from settings.json.
 * Used by users wiring an IDE / agent to the local MCP server and by integration smoke checks.
 */
@Component
@Command(name = "info", description = "Print the configured MCP connection info.", mixinStandardHelpOptions = true)
public class McpInfoCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public McpInfoCommand(CvectorRuntime runtime) {
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
        System.out.println("Bound by the dashboard / serve commands. Update with `cvector mcp update`.");
        return 0;
    }
}
