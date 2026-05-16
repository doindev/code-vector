package io.doindev.cvector;

import io.doindev.cvector.cli.CvectorCommand;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootApplication
public class CvectorApplication implements CommandLineRunner, ExitCodeGenerator {

    private static final Set<String> WEB_COMMANDS = Set.of("dashboard");
    private static final Set<String> MCP_COMMANDS = Set.of("serve");

    private final CvectorCommand rootCommand;
    private final IFactory picocliFactory;
    private int exitCode;

    public CvectorApplication(CvectorCommand rootCommand, IFactory picocliFactory) {
        this.rootCommand = rootCommand;
        this.picocliFactory = picocliFactory;
    }

    public static void main(String[] args) {
        // Scan ALL args for a known command name, not just args[0]. Earlier this only
        // looked at the first arg, which broke invocations like
        // `java -jar cvector.jar --embedded dashboard` -- args[0] there is `--embedded`,
        // so webMode flipped false and Tomcat never started. Skip-tokens-starting-with-dash
        // so we don't accidentally pick up a `--port` value that happens to spell "dashboard".
        String commandToken = "";
        for (String a : args) {
            if (a == null || a.startsWith("-")) continue;
            commandToken = a;
            break;
        }
        boolean webMode = WEB_COMMANDS.contains(commandToken);
        boolean mcpMode = MCP_COMMANDS.contains(commandToken);

        SpringApplicationBuilder builder = new SpringApplicationBuilder(CvectorApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false);

        if (webMode) {
            // Read REST host/port, server.* overrides, and mcp.transport from settings.json
            // before Spring boots. Falls back to defaults silently when the config is missing
            // (e.g. running outside a cvector workspace, or a stale install).
            int port = 2969;
            String host = "127.0.0.1";
            Map<String, Object> serverMap = null;
            String mcpTransport = CvectorConfig.McpConfig.TRANSPORT_SSE;
            String mcpSseEndpoint = null;
            try {
                CvectorConfigService svc = new CvectorConfigService();
                Path root = svc.findConfigRoot(Paths.get("").toAbsolutePath());
                if (root != null) {
                    CvectorConfig cfg = svc.load(root);
                    CvectorConfig.RestConfig rest = cfg.restOrDefault();
                    if (rest.port() != null && rest.port() > 0 && rest.port() <= 65_535) port = rest.port();
                    if (rest.host() != null && !rest.host().isBlank()) host = rest.host();
                    serverMap = rest.server();
                    CvectorConfig.McpConfig mcpCfg = cfg.mcpOrDefault();
                    mcpTransport = mcpCfg.transport();
                    mcpSseEndpoint = extractMcpSsePath(mcpCfg.url(), host, port);
                }
            } catch (Exception ignored) {
                // Boot with defaults; the operator can fix settings.json and restart.
            }
            // Co-host the MCP server with the dashboard unless the user opted out by setting
            // mcp.transport: "stdio" — stdio MCP can't be paired with an HTTP dashboard from
            // the same terminal (the JSON-RPC reader would race the user for stdin), so we
            // skip the profile entirely and `cvector dashboard` runs alone. "http" / "sse"
            // both wire Spring AI's WebMVC MCP transport into Tomcat.
            boolean coHostMcp = !CvectorConfig.McpConfig.TRANSPORT_STDIO.equalsIgnoreCase(mcpTransport);
            // Defaults via .properties() — lowest precedence, env vars and any user-set
            // settings.json values below will override.
            List<String> defaultProps = new ArrayList<>();
            defaultProps.add("spring.main.web-application-type=servlet");
            // Virtual threads serve concurrent dashboard requests cheaply. No-op on JDK <21.
            defaultProps.add("spring.threads.virtual.enabled=true");
            // Gzip JSON responses over the wire. /api/wiki etc. are ~60 KB raw
            // and compress 6-10x on text-heavy graph payloads. 1 KB floor avoids
            // overhead on tiny health-check responses.
            defaultProps.add("server.compression.enabled=true");
            defaultProps.add("server.compression.mime-types=application/json,application/javascript,text/css,text/html,text/javascript");
            defaultProps.add("server.compression.min-response-size=1024");
            // settings.json wins over env vars. We promote each setting to a JVM system
            // property — Spring Boot's property-source order puts system properties (slot 6)
            // above OS environment variables (slot 7), so any matching env var is shadowed,
            // while a real `--server.port=…` arg on the cvector command line (slot 1) still
            // beats both, which is what we want for ad-hoc overrides. System properties also
            // sidestep picocli, which would otherwise reject unknown `--server.*` tokens.
            Map<String, String> serverOverrides = new LinkedHashMap<>();
            serverOverrides.put("server.port", String.valueOf(port));
            serverOverrides.put("server.address", host);
            if (serverMap != null) flattenServerProps("server", serverMap, serverOverrides);
            // application-mcp.properties pins spring.ai.mcp.server.stdio=true for `cvector serve`.
            // When co-hosting MCP with the dashboard we want HTTP transport instead, so flip
            // stdio off and pin the message endpoint to /mcp to match the documented default
            // mcp.url. System properties (slot 6) beat profile-specific application properties
            // (slot 9), so the override here wins over what the mcp profile loads. `if
            // (System.getProperty(key) == null)` semantics in applySystemProperties still let
            // an explicit -Dspring.ai… on the cvector command line take priority.
            if (coHostMcp) {
                serverOverrides.put("spring.ai.mcp.server.stdio", "false");
                // SSE subscription endpoint: derived from settings.json mcp.url path so
                // operators who want the SSE handler at a non-default path (e.g.
                // /mcp/cvector) can express that in one place. Falls back to /sse when
                // mcp.url is absent or its path is empty.
                serverOverrides.put("spring.ai.mcp.server.sse-endpoint",
                        mcpSseEndpoint != null && !mcpSseEndpoint.isBlank() ? mcpSseEndpoint : "/sse");
                // Message POST endpoint: stays at /mcp. Spring AI emits a session-keyed
                // URL like /mcp?sessionId=… in the `endpoint` SSE event, so clients
                // never need to know this path verbatim.
                serverOverrides.put("spring.ai.mcp.server.sse-message-endpoint", "/mcp");
            }
            applySystemProperties(serverOverrides);
            builder.web(WebApplicationType.SERVLET).properties(defaultProps.toArray(String[]::new));
            if (coHostMcp) builder.profiles("mcp");
        } else if (mcpMode) {
            builder.web(WebApplicationType.NONE)
                    .profiles("mcp");
        } else {
            builder.web(WebApplicationType.NONE);
        }
        System.exit(SpringApplication.exit(builder.run(args)));
    }

    /**
     * Flattens a (possibly nested) {@code Map<String, Object>} from {@code settings.json}'s
     * {@code rest.server} section into a {@code key → value} map of Spring Boot property names.
     * Nested maps produce dotted keys (e.g. {@code ssl: {enabled: true}} →
     * {@code server.ssl.enabled=true}); lists are joined with commas (Spring Boot's binder
     * accepts that for collection-valued properties); primitives are stringified directly.
     */
    private static void flattenServerProps(String prefix, Map<String, Object> map, Map<String, String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix + "." + e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            if (val instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                flattenServerProps(key, typed, out);
            } else if (val instanceof List<?> list) {
                out.put(key, String.join(",", list.stream().map(String::valueOf).toList()));
            } else {
                out.put(key, String.valueOf(val));
            }
        }
    }

    /**
     * Parses the path component out of {@code mcp.url} so it can drive Spring AI's
     * {@code spring.ai.mcp.server.sse-endpoint} property — i.e. the URL the operator
     * configures in {@code settings.json} actually relocates the SSE handler instead
     * of being a label that drifts away from the real endpoint.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Bad / unparseable URL → returns {@code null}, caller falls back to {@code /sse}.</li>
     *   <li>URL without a path or with just {@code "/"} → returns {@code null} (default).</li>
     *   <li>URL host:port differs from the listener's host:port → prints a warning so
     *       the operator notices the mismatch (the server still listens on its own
     *       host:port — only the path is honored).</li>
     * </ul>
     */
    private static String extractMcpSsePath(String mcpUrl, String listenerHost, int listenerPort) {
        if (mcpUrl == null || mcpUrl.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(mcpUrl.trim());
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) return null;
            String urlHost = uri.getHost();
            int urlPort = uri.getPort();
            boolean hostMismatch = urlHost != null && !urlHost.equalsIgnoreCase(listenerHost)
                    && !("localhost".equalsIgnoreCase(urlHost) && listenerHost.startsWith("127."))
                    && !("127.0.0.1".equals(urlHost) && "localhost".equalsIgnoreCase(listenerHost));
            boolean portMismatch = urlPort > 0 && urlPort != listenerPort;
            if (hostMismatch || portMismatch) {
                System.err.println("warning: mcp.url host/port (" + urlHost + ":" + urlPort
                        + ") differs from rest.host:rest.port (" + listenerHost + ":" + listenerPort
                        + "); the server still binds at the rest.* values, only the path '" + path + "' is honored.");
            }
            return path;
        } catch (Exception e) {
            System.err.println("warning: could not parse mcp.url '" + mcpUrl + "': " + e.getMessage()
                    + " — falling back to /sse");
            return null;
        }
    }

    /**
     * Sets each entry as a JVM system property, but only when the same property isn't already
     * set explicitly on the command line (so {@code -Dserver.port=…} still wins over the
     * settings.json value, preserving the usual "outermost layer wins" semantics).
     */
    private static void applySystemProperties(Map<String, String> props) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public void run(String... args) {
        this.exitCode = new CommandLine(rootCommand, picocliFactory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
