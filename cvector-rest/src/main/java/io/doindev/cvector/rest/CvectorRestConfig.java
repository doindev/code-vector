package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.Neo4jGraphStore;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the REST module's beans against either Neo4j or the embedded KuzuDB store, controlled by
 * {@code cvector.embedded} / {@code CVECTOR_EMBEDDED}. The {@link GraphStore} bean owns the
 * connection; {@link GraphQueries} is exposed as a Neo4j-only escape hatch for the few endpoints
 * that still issue raw Cypher (shortestPath, etc.).
 */
@Configuration
@ConditionalOnWebApplication
public class CvectorRestConfig {

    /**
     * Backend selection precedence:
     * <ol>
     *   <li>{@code cvector.embedded=true} system property (legacy opt-in, still wins for back-compat).</li>
     *   <li>{@code CVECTOR_EMBEDDED=true} env var (legacy opt-in).</li>
     *   <li>{@code settings.json} {@code backend} field — {@code embedded} | {@code remote} | {@code docker}.</li>
     *   <li>Default: {@code embedded}.</li>
     * </ol>
     * The CLI's {@code --remote} / {@code --docker} flags translate into the settings.json field
     * (or a one-shot system property override) rather than going through their own boot path.
     */
    private static String resolveBackend(CvectorConfig cfg) {
        if (Boolean.getBoolean("cvector.embedded")) return CvectorConfig.BACKEND_EMBEDDED;
        String env = System.getenv("CVECTOR_EMBEDDED");
        if (env != null && env.equalsIgnoreCase("true")) return CvectorConfig.BACKEND_EMBEDDED;
        String override = System.getProperty("cvector.backend");
        if (override != null && !override.isBlank()) return override.toLowerCase();
        return cfg.backendOrDefault();
    }

    @Bean(destroyMethod = "close")
    public GraphStore restGraphStore(CvectorConfigService configService, ActiveProject activeProject) {
        CvectorConfig cfg = loadConfig(configService);
        String backend = resolveBackend(cfg);
        if (CvectorConfig.BACKEND_EMBEDDED.equals(backend)) {
            Path db = EmbeddedKuzu.defaultDbPath(cfg, activeProject.projectId());
            try {
                EmbeddedKuzu kuzu = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg));
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                return new KuzuGraphStore(kuzu);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open embedded kuzu at " + db, e);
            }
        }
        if (CvectorConfig.BACKEND_DOCKER.equals(backend)) {
            // Best-effort container bring-up. We don't block REST startup on docker compose
            // failure — the user can fix Docker and restart — but waiting for bolt readiness
            // before opening the driver avoids a confusing "connection refused" race.
            Path root = Paths.get("").toAbsolutePath();
            Path configRoot = configService.findConfigRoot(root);
            if (configRoot != null) {
                DockerNeo4jBackend.ensureRunning(configService, configRoot, cfg.dockerOrDefault());
            }
        }
        // Both "remote" and "docker" connect through the Neo4j driver — the difference is that
        // "docker" implies the app is responsible for the container lifecycle (handled above).
        CvectorConfig.Neo4jConfig n = cfg.neo4jOrDefault();
        return new Neo4jGraphStore(new Neo4jClient(n.uri(), n.user(), n.password()));
    }

    /** Neo4j-only escape hatch for endpoints that still emit raw Cypher. Null on embedded. */
    @Bean
    public GraphQueries restLegacyGraphQueries(GraphStore restGraphStore) {
        if (restGraphStore instanceof Neo4jGraphStore neo) return neo.queries();
        return null;
    }

    /**
     * Bumps Tomcat's NIO socket write buffer from the 8 KiB default to 64 KiB so MCP
     * SSE event frames carrying tool responses (cv_list_projects with rich metadata,
     * cv_onboard with full briefing, cv_search with many hits) don't blow up with
     * {@code java.nio.BufferOverflowException} mid-write.
     *
     * <p><b>Why 64 KiB and not bigger.</b> An earlier version set this to 256 KiB.
     * Under concurrent SSE writes that triggered a second, opposite NIO bug:
     * {@code IllegalArgumentException: newPosition > limit: (262144 > 1871)} from
     * {@code IOUtil.write} — Tomcat's app-level write buffer was much larger than the
     * temporary direct buffer the JDK NIO layer allocated for the actual send,
     * and the position-tracking code overflowed. 64 KiB covers every realistic
     * MCP SSE event we produce (the largest measured is ~30 KiB) while staying
     * close enough to typical OS SO_SNDBUF that the direct-buffer copy stays
     * within bounds. If a future tool starts producing >64 KiB SSE events, watch
     * for {@code BufferOverflowException} again and raise this value carefully —
     * but pair the raise with a stress test of concurrent SSE writes.
     */
    @Bean
    public org.springframework.boot.web.server.WebServerFactoryCustomizer<
            org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> tomcatWriteBufferCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("socket.appWriteBufSize", "65536");
            connector.setProperty("socket.appReadBufSize", "65536");
        });
    }

    /**
     * Active-project holder bean. Returns a placeholder {@code ActiveProject(null, null, null)}
     * when the workspace has no projects registered yet (or activeProject points at a
     * deleted entry) — that lets the dashboard / REST server / MCP boot fine for an
     * empty workspace so the user can immediately call {@code cv_add_project} /
     * {@code cv_onboard_project} or hit the dashboard's "add project" flow.
     *
     * <p>Controllers reading {@link ActiveProject#projectId()} get {@code null} in that
     * case and are expected to return a clean 4xx/empty payload — see {@link CacheWarmer}
     * for the existing "skip work if no project" pattern.
     */
    @Bean
    public ActiveProject activeProject(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        if (cfg.activeProject() == null || !cfg.projects().containsKey(cfg.activeProject())) {
            return new ActiveProject(null, null, null);
        }
        CvectorConfig.ProjectEntry e = cfg.projects().get(cfg.activeProject());
        return new ActiveProject(e.projectId(), e.name(), e.rootPath());
    }

    private static CvectorConfig loadConfig(CvectorConfigService configService) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException(
                    "No .cvector/settings.json found from " + cwd
                            + " (also checked your user home directory). Run `cvector init` "
                            + "inside the project, or create a global workspace at "
                            + "~/.cvector/settings.json.");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
