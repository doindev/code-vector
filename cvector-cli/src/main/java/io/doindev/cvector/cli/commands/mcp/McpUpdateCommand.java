package io.doindev.cvector.cli.commands.mcp;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cvector mcp update --transport <http|sse|stdio> <URL>}. Writes the mcp section of
 * settings.json and exits. Validates transport up front so an invalid value can't silently
 * land in the file. The legacy {@code streamable} alias is accepted and stored as
 * {@code http}.
 */
@Component
@Command(name = "update", description = "Update the MCP connection settings in .cvector/settings.json.",
        mixinStandardHelpOptions = true)
public class McpUpdateCommand implements Callable<Integer> {

    @Option(names = "--transport", description = "Transport: http (default), sse, or stdio. "
            + "'streamable' is accepted as a legacy alias for http.",
            defaultValue = CvectorConfig.McpConfig.TRANSPORT_HTTP)
    private String transport;

    @Parameters(index = "0", arity = "0..1",
            description = "MCP URL clients will connect to (e.g. http://127.0.0.1:2969/mcp). "
                    + "Omit to keep the existing URL.")
    private String url;

    private final CvectorRuntime runtime;

    public McpUpdateCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        if (!CvectorConfig.McpConfig.isValidTransport(transport)) {
            System.err.println("invalid --transport: '" + transport + "' (allowed: http, sse, stdio; 'streamable' accepted as alias for http)");
            return 2;
        }
        Path root = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        String canonical = CvectorConfig.McpConfig.canonicalTransport(transport);
        CvectorConfig updated = svc.update(root, before -> {
            CvectorConfig.McpConfig current = before.mcpOrDefault();
            String newUrl = (url == null || url.isBlank()) ? current.url() : url;
            // Preserve any existing timeouts the user set via settings.json or the REST API —
            // `cvector mcp update` only changes url + transport.
            CvectorConfig.McpConfig next = new CvectorConfig.McpConfig(newUrl, canonical, current.timeouts());
            return new CvectorConfig(
                    before.activeProject(), before.projects(), before.neo4j(),
                    before.backend(), before.rest(), next, before.docker(), before.rules(), before.kuzu());
        });
        CvectorConfig.McpConfig mcp = updated.mcpOrDefault();
        System.out.println("updated MCP settings:");
        System.out.println("  transport: " + mcp.transport());
        System.out.println("  url:       " + mcp.url());
        System.out.println("  file:      " + svc.configPath(root));
        System.out.println();
        System.out.println("Restart the dashboard / serve process to pick up the new transport.");
        return 0;
    }
}
