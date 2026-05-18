package io.doindev.cvector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.cli.CvectorCommand;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
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
            // In-place restart handshake: when this JVM was spawned by a previous cvector
            // instance's RestartRunner, it carries a `-Dcvector.restart.waitForPid=<pid>`
            // pointing at the soon-to-exit parent. Hold here until that PID is gone so Kuzu's
            // directory lock has been released by the OS before Spring boots EmbeddedKuzu.
            // No-op on a plain `cvector dashboard` launch (the property won't be set).
            awaitParentExit();
            // Read REST host/port, server.* overrides, and mcp.transport from settings.json
            // before Spring boots. Falls back to defaults silently when the config is missing
            // (e.g. running outside a cvector workspace, or a stale install).
            int port = 2969;
            String host = "127.0.0.1";
            Map<String, Object> serverMap = null;
            String mcpTransport = CvectorConfig.McpConfig.TRANSPORT_HTTP;
            String mcpHttpPath = null;
            CvectorConfig.McpConfig.McpTimeouts mcpTimeouts = null;
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
                    mcpTransport = CvectorConfig.McpConfig.canonicalTransport(mcpCfg.transport());
                    mcpHttpPath = extractMcpSsePath(mcpCfg.url(), host, port);
                    mcpTimeouts = mcpCfg.timeouts();
                }
            } catch (Exception ignored) {
                // Boot with defaults; the operator can fix settings.json and restart.
            }
            // Co-host the MCP server with the dashboard unless the user opted out by setting
            // mcp.transport: "stdio" — stdio MCP can't be paired with an HTTP dashboard from
            // the same terminal (the JSON-RPC reader would race the user for stdin), so we
            // skip the profile entirely and `cvector dashboard` runs alone. "http" / "sse"
            // both wire Spring AI's WebMVC MCP transport into Tomcat — which one is
            // determined below by spring.ai.mcp.server.protocol.
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
            // stdio off and select the active HTTP protocol via spring.ai.mcp.server.protocol
            // (Spring AI 2.0 picks ONE of SSE / STREAMABLE / STATELESS based on this property
            // and conditionally wires the matching auto-config). System properties (slot 6)
            // beat profile-specific application properties (slot 9), so these overrides win
            // over what the mcp profile loads. `if (System.getProperty(key) == null)`
            // semantics in applySystemProperties still let an explicit -Dspring.ai… on the
            // cvector command line take priority.
            if (coHostMcp) {
                serverOverrides.put("spring.ai.mcp.server.stdio", "false");
                if (CvectorConfig.McpConfig.TRANSPORT_SSE.equalsIgnoreCase(mcpTransport)) {
                    // MCP 2024-11-05 "HTTP+SSE" transport — two endpoints, sessionId on the
                    // query param. Spring AI emits the per-session URL like
                    // /mcp/message?sessionId=… in the `endpoint` SSE event, so clients
                    // never need to know the POST path verbatim. We honour mcp.url's path
                    // component as the SSE GET path so an operator who set the URL to
                    // .../sse/cvector gets the handler at the matching location.
                    serverOverrides.put("spring.ai.mcp.server.protocol", "SSE");
                    serverOverrides.put("spring.ai.mcp.server.sse-endpoint",
                            mcpHttpPath != null && !mcpHttpPath.isBlank() ? mcpHttpPath : "/sse");
                    serverOverrides.put("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message");
                } else {
                    // MCP 2025-03-26 "Streamable HTTP" transport — single endpoint, session
                    // on Mcp-Session-Id header. Default for new installs; modern MCP clients
                    // (Eclipse Copilot, MCP Inspector v2, newer Claude integrations) speak
                    // this. mcp.url's path component drives the endpoint location, falling
                    // back to /mcp.
                    serverOverrides.put("spring.ai.mcp.server.protocol", "STREAMABLE");
                    serverOverrides.put("spring.ai.mcp.server.streamable-http.mcp-endpoint",
                            mcpHttpPath != null && !mcpHttpPath.isBlank() ? mcpHttpPath : "/mcp");
                }
                applyMcpTimeouts(mcpTransport, mcpTimeouts, serverOverrides);
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
     * Translate the optional {@code mcp.timeouts} settings.json section into the matching
     * Spring AI / Spring MVC system properties. Each field is independent and only emits a
     * property when non-null — a {@code null} (or omitted) field leaves the application-mcp
     * default in place. The keep-alive property key depends on which HTTP transport is
     * active because Spring AI uses different config sub-namespaces for SSE vs Streamable.
     *
     * <p>This method only writes to the {@code serverOverrides} map; the caller's existing
     * {@link #applySystemProperties} step is what actually pushes them onto the JVM, so the
     * "command-line -D wins" semantics still apply to these knobs.
     */
    private static void applyMcpTimeouts(String transport,
                                         CvectorConfig.McpConfig.McpTimeouts timeouts,
                                         Map<String, String> serverOverrides) {
        if (timeouts == null) return;
        if (timeouts.requestTimeoutMs() != null && timeouts.requestTimeoutMs() > 0) {
            // Spring AI parses java.time.Duration; the `ms` suffix is the canonical
            // milliseconds form recognised by Spring Boot's Duration binder.
            serverOverrides.put("spring.ai.mcp.server.request-timeout",
                    timeouts.requestTimeoutMs() + "ms");
        }
        if (timeouts.keepAliveIntervalMs() != null && timeouts.keepAliveIntervalMs() > 0) {
            // Streamable HTTP and SSE expose keep-alive under different property keys.
            // Pick the matching one so the same settings.json field works regardless of
            // which transport the user picked.
            String key = CvectorConfig.McpConfig.TRANSPORT_SSE.equalsIgnoreCase(transport)
                    ? "spring.ai.mcp.server.keep-alive-interval"
                    : "spring.ai.mcp.server.streamable-http.keep-alive-interval";
            serverOverrides.put(key, timeouts.keepAliveIntervalMs() + "ms");
        }
        if (timeouts.asyncRequestTimeoutMs() != null) {
            // -1 disables the timeout (Spring MVC's "no async ceiling") — application-mcp's
            // default. Positive values evict idle SSE / Streamable-HTTP streams sooner.
            serverOverrides.put("spring.mvc.async.request-timeout",
                    String.valueOf(timeouts.asyncRequestTimeoutMs()));
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

    /**
     * Block until the PID named by {@code -Dcvector.restart.waitForPid=<pid>} has exited,
     * so the in-place dashboard restart can be sure the Kuzu directory lock the old JVM held
     * has been released by the OS before the new JVM tries to open it. The
     * {@code RestartRunner} on the parent side passes its own PID through this system
     * property when it spawns the replacement; a plain {@code cvector dashboard} launch from
     * a shell never carries it and this is a no-op.
     *
     * <p>Polls {@code ProcessHandle.of(pid).isPresent()} every 500 ms. Bounded at 30 s — past
     * that we log a warning and fall through, letting Spring boot proceed. {@code EmbeddedKuzu}'s
     * own retry-with-backoff (also ~30 s ceiling) catches the rare case where the parent
     * truly froze during shutdown and Kuzu's lock didn't release.
     *
     * <p>Logs to {@code System.err} directly rather than via SLF4J because logging hasn't
     * been initialised at this point in startup — the operator sees the wait outcome live in
     * the terminal that holds the new process.
     */
    private static void awaitParentExit() {
        String raw = System.getProperty("cvector.restart.waitForPid");
        if (raw == null || raw.isBlank()) return;
        long pid;
        try {
            pid = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("restart: ignoring malformed cvector.restart.waitForPid=" + raw);
            return;
        }
        if (!ProcessHandle.of(pid).isPresent()) {
            // Parent already gone by the time we got here. Common when the parent's
            // System.exit(0) finished before PowerShell finished spawning us.
            return;
        }
        System.err.println("restart: waiting for parent pid=" + pid + " to exit before opening Kuzu");
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!ProcessHandle.of(pid).isPresent()) {
                long waited = 30_000L - (deadline - System.currentTimeMillis());
                System.err.println("restart: parent pid=" + pid + " exited after " + waited + " ms");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.err.println("restart: interrupted while waiting for parent pid=" + pid);
                return;
            }
        }
        // 30 s ceiling — let EmbeddedKuzu's retry loop handle the rare case where the
        // parent process froze during shutdown and the lock didn't release on time.
        System.err.println("restart: parent pid=" + pid + " still alive after 30 s; proceeding anyway"
                + " (EmbeddedKuzu lock retry will cover any straggler release)");
    }

    @Override
    public void run(String... args) {
        this.exitCode = new CommandLine(rootCommand, picocliFactory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Jackson 2 {@link ObjectMapper} bean — restored manually because Spring Boot 4 dropped
     * the {@code JacksonAutoConfiguration} that used to auto-create one. Boot 4 now ships
     * the new {@code tools.jackson.databind.json.JsonMapper} (Jackson 3) as the default,
     * but most of cvector still wires the Jackson 2 type (JsonCache, SettingsController,
     * AuditService, CvectorTools, ...). Rather than migrate every call site to Jackson 3,
     * we keep Jackson 2 on the classpath (already transitively present) and re-expose its
     * ObjectMapper here. {@code @ConditionalOnMissingBean} keeps the door open for a
     * future migration: any module that wants to provide a customised ObjectMapper —
     * e.g. with extra modules registered — can just declare its own bean and ours steps
     * aside.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
