package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Component
@Command(name = "dashboard",
        description = "Start the cvector REST API server (default port 2969). Pass --open to launch the dashboard UI in the default browser.",
        mixinStandardHelpOptions = true)
public class DashboardCommand implements Callable<Integer> {

    @Option(names = {"--open", "-o"},
            description = "Open the dashboard UI in the default browser once the server is up.")
    private boolean openBrowser;

    @Option(names = "--port",
            description = "Port to bind (default 2969). Overrides server.port from application.properties.")
    private Integer port;

    private final CvectorRuntime runtime;

    public DashboardCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws InterruptedException {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        int effectivePort = port != null ? port : 2969;
        String backend = CvectorRuntime.isEmbeddedRequested()
                ? "kuzu (embedded)"
                : "neo4j @ " + cfg.neo4jOrDefault().uri();
        String base = "http://localhost:" + effectivePort;
        CvectorConfig.McpConfig mcp = cfg.mcpOrDefault();
        // mcp.transport drives whether MCP is co-hosted with the dashboard. "stdio" opts
        // out (dashboard runs alone, MCP is only available via `cvector serve`); anything
        // else (http / sse) co-hosts the WebMvc SSE transport at /sse with the message
        // endpoint at /mcp?sessionId=…. The banner reflects the live wiring so operators
        // don't have to cross-reference the settings.json file to figure out what their
        // running process actually exposes.
        boolean mcpCoHosted = !CvectorConfig.McpConfig.TRANSPORT_STDIO.equalsIgnoreCase(mcp.transport());
        String mcpLine = mcpCoHosted
                ? "sse @ " + base + "/sse  (transport=" + mcp.transport() + ")"
                : "stdio only — run `cvector serve` for MCP";
        System.out.println("cvector dashboard running");
        System.out.println("  project:    " + active.name() + " (" + active.projectId() + ")");
        System.out.println("  backend:    " + backend);
        System.out.println("  api:        " + base + "/api");
        System.out.println("  dashboard:  " + base + "/dashboard");
        System.out.println("  mcp:        " + mcpLine);
        System.out.println();
        System.out.println("Endpoints:");
        for (String e : new String[]{
                "GET /api/health",
                "GET /api/stats",
                "GET /api/projects",
                "GET /api/search?q=<symbol>",
                "GET /api/explain?symbol=<symbol>",
                "GET /api/impact?symbol=<symbol>[&depth=N]",
                "GET /api/test-impact?symbol=<symbol>[&depth=N]",
                "GET /api/dashboard/status"
        }) {
            System.out.println("  " + e);
        }
        System.out.println();
        System.out.println("Press Ctrl-C to stop.");

        if (openBrowser) {
            // Launch in a background thread so a slow browser open doesn't block the
            // shutdown latch below. CommandLineRunner runs after Spring's context refresh
            // (which boots Tomcat), but on a cold start the banner can print seconds
            // before the bind is actually accepting traffic -- and on slow machines the
            // MCP profile's eager bean wiring extends boot to 30+ seconds. Poll
            // /api/health until it responds (or give up after ~30 s) so the browser only
            // opens once the user can actually see something.
            URI url = URI.create(base + "/dashboard/");
            Thread opener = new Thread(() -> {
                if (!waitForHealth(base + "/api/health", 30_000L)) {
                    System.out.println("(--open requested but /api/health didn't respond in 30 s; visit " + url + " manually)");
                    return;
                }
                if (openInBrowser(url)) {
                    System.out.println("opened " + url + " in default browser");
                } else {
                    System.out.println("(--open requested but no opener available; visit " + url + " manually)");
                }
            }, "cvector-open-browser");
            opener.setDaemon(true);
            opener.start();
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("shutting down...");
            shutdown.countDown();
        }, "cvector-shutdown"));
        shutdown.await();
        return 0;
    }

    /**
     * Try every viable way to open a URL in the user's default browser, in order:
     * Java's {@link Desktop} API first, then the platform's native shell helper.
     *
     * <p>Desktop API alone isn't enough because jpackage's {@code APP_IMAGE} launcher
     * starts the JVM without a GUI session attached, so {@link Desktop#isDesktopSupported()}
     * (or {@link Desktop.Action#BROWSE} support specifically) returns false. The
     * fallback path shells out to the OS's url-handler binary, which works regardless
     * of how the JVM was launched:
     * <ul>
     *   <li>Windows: {@code rundll32 url.dll,FileProtocolHandler <url>} — the same
     *       mechanism Explorer uses internally, works headless.</li>
     *   <li>macOS: {@code open <url>}.</li>
     *   <li>Linux / BSD: {@code xdg-open <url>}.</li>
     * </ul>
     *
     * <p>Returns {@code true} as soon as any method appears to have succeeded
     * (Desktop.browse returned without throwing, or the spawned subprocess started).
     * Returns {@code false} only when every path fails, which is when the caller
     * tells the user to paste the URL manually.
     */
    /**
     * Polls {@code /api/health} every 500 ms until it returns 200 or {@code maxWaitMs}
     * elapses. Used by the {@code --open} flow so we only open the browser after the
     * server is actually accepting traffic; otherwise on slow boots (MCP profile eager
     * init can stretch first-time startup past 20 s) the browser races Spring Boot and
     * the user sees {@code ERR_CONNECTION_REFUSED} on the new tab.
     */
    private static boolean waitForHealth(String healthUrl, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        URL url;
        try { url = URI.create(healthUrl).toURL(); }
        catch (Exception e) { return false; }
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(500);
                c.setReadTimeout(500);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) return true;
            } catch (Exception ignored) {
                // Connection refused / SocketTimeoutException — keep polling.
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean openInBrowser(URI url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to OS shell helpers below.
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url.toString())
                        .inheritIO().start();
                return true;
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", url.toString()).inheritIO().start();
                return true;
            }
            // Most Linux desktop environments ship xdg-open. Falls through to manual
            // paste if it isn't installed (headless servers, minimal containers, etc.).
            new ProcessBuilder("xdg-open", url.toString()).inheritIO().start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
