package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bring-up helper for the {@code docker} backend mode. When the workspace's settings.json picks
 * {@code backend: "docker"}, the REST layer calls {@link #ensureRunning} before the Neo4j
 * driver tries to connect. The helper:
 *
 * <ol>
 *   <li>Writes {@code .cvector/docker-compose.yml} from the {@link CvectorConfig.DockerConfig}
 *       defaults if it doesn't already exist (so first-run after {@code cvector db --docker}
 *       just works without a separate scaffold step).</li>
 *   <li>Checks whether the bolt port is already accepting connections — if so, assumes the
 *       container is already up and skips the {@code docker compose} call.</li>
 *   <li>Runs {@code docker compose -f .cvector/docker-compose.yml up -d} and waits for the
 *       bolt port to become reachable (max ~60 s).</li>
 * </ol>
 *
 * <p>If anything fails — Docker missing, compose file invalid, port never opens — we log a
 * loud warning and let the Neo4j driver throw the connection error downstream. We never try
 * to manage the container lifecycle on shutdown; the user owns the container.
 */
public final class DockerNeo4jBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerNeo4jBackend.class);

    /** Max wall time we'll wait for bolt to come up. */
    private static final long BOLT_READY_TIMEOUT_MS = 60_000;
    /** Polling cadence while waiting. */
    private static final long BOLT_POLL_INTERVAL_MS = 1_000;

    private DockerNeo4jBackend() {}

    public static void ensureRunning(CvectorConfigService configService, Path workspaceRoot, CvectorConfig.DockerConfig docker) {
        CvectorConfig.DockerConfig cfg = docker == null ? CvectorConfig.DockerConfig.defaults() : docker.withDefaults();
        int boltPort = cfg.boltPort();

        if (isPortOpen("127.0.0.1", boltPort, 500)) {
            log.info("docker backend: bolt port {} already reachable, assuming Neo4j is up", boltPort);
            return;
        }

        Path compose = configService.configDir(workspaceRoot).resolve("docker-compose.yml");
        try {
            if (!Files.exists(compose)) {
                Files.createDirectories(compose.getParent());
                Files.writeString(compose, renderCompose(cfg));
                log.info("docker backend: wrote {}", compose);
            }
            log.info("docker backend: `docker compose -f {} up -d`", compose);
            int code = runProcess("docker", "compose", "-f", compose.toString(), "up", "-d");
            if (code != 0) {
                log.warn("docker compose exited {} -- the Neo4j driver will surface a connection failure", code);
                return;
            }
        } catch (IOException | InterruptedException e) {
            log.warn("docker backend: failed to start container ({}), continuing -- connection failure will be reported by the driver", e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return;
        }

        long deadline = System.currentTimeMillis() + BOLT_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen("127.0.0.1", boltPort, 1_000)) {
                log.info("docker backend: bolt port {} ready", boltPort);
                return;
            }
            try { Thread.sleep(BOLT_POLL_INTERVAL_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("docker backend: bolt port {} never opened within {}s -- connection will fail downstream",
                boltPort, BOLT_READY_TIMEOUT_MS / 1000);
    }

    /** TCP probe; true when something is bound to host:port and accepting connections. */
    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int runProcess(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    /** Render a compose file from the DockerConfig. Mirrors InitCommand's COMPOSE_TEMPLATE shape. */
    private static String renderCompose(CvectorConfig.DockerConfig cfg) {
        // Note: password lives in NEO4J_AUTH; this matches the existing template. Users wanting
        // a custom password can edit the compose file directly — we don't currently surface it
        // in the DockerConfig since the bolt URL/auth in the Neo4jConfig section already drives
        // the driver-side credentials.
        return "services:\n"
                + "  neo4j:\n"
                + "    image: " + cfg.image() + ":" + cfg.neo4jVersion() + "\n"
                + "    container_name: " + cfg.containerName() + "\n"
                + "    ports:\n"
                + "      - \"" + cfg.httpPort() + ":7474\"\n"
                + "      - \"" + cfg.boltPort() + ":7687\"\n"
                + "    environment:\n"
                + "      NEO4J_AUTH: neo4j/neo4jneo4j\n"
                + "      NEO4J_dbms_memory_heap_initial__size: 512m\n"
                + "      NEO4J_dbms_memory_heap_max__size: 2G\n"
                + "    volumes:\n"
                + "      - cvector_neo4j_data:/data\n"
                + "volumes:\n"
                + "  cvector_neo4j_data:\n";
    }
}
