package io.doindev.cvector;

import io.doindev.cvector.cli.CvectorCommand;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

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
            builder.web(WebApplicationType.SERVLET)
                    .properties(
                            "spring.main.web-application-type=servlet",
                            "server.port=2969",
                            // Virtual threads serve concurrent dashboard requests cheaply. No-op on JDK <21.
                            "spring.threads.virtual.enabled=true",
                            // Gzip JSON responses over the wire. /api/wiki etc. are ~60 KB raw
                            // and compress 6-10x on text-heavy graph payloads. 1 KB floor avoids
                            // overhead on tiny health-check responses.
                            "server.compression.enabled=true",
                            "server.compression.mime-types=application/json,application/javascript,text/css,text/html,text/javascript",
                            "server.compression.min-response-size=1024"
                    );
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
