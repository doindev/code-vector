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

    private static boolean embeddedRequested() {
        if (Boolean.getBoolean("cvector.embedded")) return true;
        String env = System.getenv("CVECTOR_EMBEDDED");
        return env != null && env.equalsIgnoreCase("true");
    }

    @Bean(destroyMethod = "close")
    public GraphStore restGraphStore(CvectorConfigService configService, ActiveProject activeProject) {
        CvectorConfig cfg = loadConfig(configService);
        if (embeddedRequested()) {
            Path db = EmbeddedKuzu.defaultDbPath(activeProject.projectId());
            try {
                EmbeddedKuzu kuzu = new EmbeddedKuzu(db);
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                return new KuzuGraphStore(kuzu);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open embedded kuzu at " + db, e);
            }
        }
        CvectorConfig.Neo4jConfig n = cfg.neo4j() != null ? cfg.neo4j() : CvectorConfig.Neo4jConfig.defaults();
        return new Neo4jGraphStore(new Neo4jClient(n.uri(), n.user(), n.password()));
    }

    /** Neo4j-only escape hatch for endpoints that still emit raw Cypher. Null on embedded. */
    @Bean
    public GraphQueries restLegacyGraphQueries(GraphStore restGraphStore) {
        if (restGraphStore instanceof Neo4jGraphStore neo) return neo.queries();
        return null;
    }

    @Bean
    public ActiveProject activeProject(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        if (cfg.activeProject() == null || !cfg.projects().containsKey(cfg.activeProject())) {
            throw new IllegalStateException("No active project in cvector config — run `cvector init` and a `cvector scan` first.");
        }
        CvectorConfig.ProjectEntry e = cfg.projects().get(cfg.activeProject());
        return new ActiveProject(e.projectId(), e.name(), e.rootPath());
    }

    private static CvectorConfig loadConfig(CvectorConfigService configService) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException(
                    "No .cvector/project.json found from " + cwd + ". Run `cvector init` first.");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
