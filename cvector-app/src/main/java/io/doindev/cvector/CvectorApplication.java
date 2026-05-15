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
import java.util.List;
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
            // Read REST host/port from settings.json before Spring boots so Tomcat binds to
            // the user-configured address. Falls back to defaults silently when the config
            // is missing (e.g. running outside a cvector workspace, or a stale install).
            int port = 2969;
            String host = "127.0.0.1";
            try {
                CvectorConfigService svc = new CvectorConfigService();
                Path root = svc.findConfigRoot(Paths.get("").toAbsolutePath());
                if (root != null) {
                    CvectorConfig cfg = svc.load(root);
                    CvectorConfig.RestConfig rest = cfg.restOrDefault();
                    if (rest.port() != null && rest.port() > 0 && rest.port() <= 65_535) port = rest.port();
                    if (rest.host() != null && !rest.host().isBlank()) host = rest.host();
                }
            } catch (Exception ignored) {
                // Boot with defaults; the operator can fix settings.json and restart.
            }
            List<String> props = new ArrayList<>();
            props.add("spring.main.web-application-type=servlet");
            props.add("server.port=" + port);
            props.add("server.address=" + host);
            // Virtual threads serve concurrent dashboard requests cheaply. No-op on JDK <21.
            props.add("spring.threads.virtual.enabled=true");
            // Gzip JSON responses over the wire. /api/wiki etc. are ~60 KB raw
            // and compress 6-10x on text-heavy graph payloads. 1 KB floor avoids
            // overhead on tiny health-check responses.
            props.add("server.compression.enabled=true");
            props.add("server.compression.mime-types=application/json,application/javascript,text/css,text/html,text/javascript");
            props.add("server.compression.min-response-size=1024");
            builder.web(WebApplicationType.SERVLET)
                    .profiles("mcp")
                    .properties(props.toArray(String[]::new));
        } else if (mcpMode) {
            builder.web(WebApplicationType.NONE)
                    .profiles("mcp");
        } else {
            builder.web(WebApplicationType.NONE);
        }
        System.exit(SpringApplication.exit(builder.run(args)));
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
