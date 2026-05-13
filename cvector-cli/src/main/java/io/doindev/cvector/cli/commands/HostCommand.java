package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cvector host <127.0.0.1|0.0.0.0|localhost|...>} — flip the REST + dashboard bind
 * address in settings.json. Defaults stay at {@code 127.0.0.1}; switching to {@code 0.0.0.0}
 * gets a loud warning because it exposes the API to anything on the network.
 *
 * <p>Invoke without a positional arg to print the current host instead.
 */
@Component
@Command(name = "host", description = "Show or set the REST/dashboard bind address (default 127.0.0.1).",
        mixinStandardHelpOptions = true)
public class HostCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Bind address (e.g. 127.0.0.1, localhost, 0.0.0.0). Omit to print the current value.")
    private String host;

    private final CvectorRuntime runtime;

    public HostCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path root = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        if (host == null || host.isBlank()) {
            CvectorConfig cfg = svc.load(root);
            System.out.println("host: " + cfg.restOrDefault().host());
            return 0;
        }
        String normalized = host.trim().toLowerCase();
        if (!isValidHost(normalized)) {
            System.err.println("refusing to set host to '" + host + "': value should be 127.0.0.1, "
                    + "localhost, 0.0.0.0, or a literal IPv4 / IPv6 address.");
            return 2;
        }
        CvectorConfig updated = svc.update(root, before -> {
            CvectorConfig.RestConfig current = before.restOrDefault();
            CvectorConfig.RestConfig next = new CvectorConfig.RestConfig(current.port(), normalized);
            return new CvectorConfig(
                    before.activeProject(), before.projects(), before.neo4j(),
                    before.backend(), next, before.mcp(), before.docker());
        });
        CvectorConfig.RestConfig rest = updated.restOrDefault();
        System.out.println("host set to " + rest.host() + " (port " + rest.port() + ")");
        if ("0.0.0.0".equals(rest.host()) || "::".equals(rest.host())) {
            System.out.println();
            System.out.println("WARNING: the REST API and dashboard are now reachable from any network "
                    + "interface. cvector exposes write endpoints (settings, scan trigger, etc) and does "
                    + "not authenticate — make sure the host is behind a firewall, reverse proxy, or VPN.");
        }
        System.out.println();
        System.out.println("Restart the dashboard / serve process to bind the new address.");
        return 0;
    }

    private static boolean isValidHost(String h) {
        if (h == null || h.isBlank()) return false;
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::") || h.equals("::1")) {
            return true;
        }
        // Permissive IPv4 dotted-quad / hostname / IPv6 — Tomcat will reject anything truly bogus
        // when it tries to bind. We block obvious shell-injection-ish strings.
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':' || c == '_')) return false;
        }
        return true;
    }
}
