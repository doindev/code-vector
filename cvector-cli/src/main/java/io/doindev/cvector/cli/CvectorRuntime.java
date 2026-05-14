package io.doindev.cvector.cli;

import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.Neo4jGraphStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class CvectorRuntime {

    private final CvectorConfigService configService;

    public CvectorRuntime(CvectorConfigService configService) {
        this.configService = configService;
    }

    public CvectorConfigService configService() { return configService; }

    public Path workingDir() {
        return Paths.get("").toAbsolutePath();
    }

    public Path resolveConfigRoot() {
        Path root = configService.findConfigRoot(workingDir());
        if (root == null) {
            throw new IllegalStateException(
                    "No .cvector/settings.json found in " + workingDir()
                            + " or any parent (also checked your user home directory). "
                            + "Run `cvector init` inside the project, or create a global "
                            + "workspace at ~/.cvector/settings.json so commands work from "
                            + "anywhere.");
        }
        return root;
    }

    public CvectorConfig loadConfig() {
        Path root = resolveConfigRoot();
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load " + configService.configPath(root), e);
        }
    }

    public CvectorConfig.ProjectEntry requireActiveProject(CvectorConfig cfg) {
        if (cfg.activeProject() == null) {
            throw new IllegalStateException("No active project. Use `cvector project switch <name>` or set one.");
        }
        CvectorConfig.ProjectEntry entry = cfg.projects().get(cfg.activeProject());
        if (entry == null) {
            throw new IllegalStateException("Active project '" + cfg.activeProject() + "' not in config.projects map.");
        }
        return entry;
    }

    public Neo4jClient openNeo4j(CvectorConfig cfg) {
        CvectorConfig.Neo4jConfig n = cfg.neo4j() != null ? cfg.neo4j() : CvectorConfig.Neo4jConfig.defaults();
        return new Neo4jClient(n.uri(), n.user(), n.password());
    }

    /**
     * Open the right read store for the active backend. Callers should close the returned store.
     * The Kuzu store assumes the schema has already been bootstrapped (it's safe to re-bootstrap;
     * {@code CREATE NODE TABLE IF NOT EXISTS} is idempotent).
     */
    public GraphStore openGraphStore(CvectorConfig cfg) {
        String backend = resolveBackend(cfg);
        if (CvectorConfig.BACKEND_EMBEDDED.equals(backend)) {
            CvectorConfig.ProjectEntry entry = requireActiveProject(cfg);
            Path db = EmbeddedKuzu.defaultDbPath(entry.projectId());
            try {
                EmbeddedKuzu kuzu = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg));
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                return new KuzuGraphStore(kuzu);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open embedded kuzu at " + db, e);
            }
        }
        return new Neo4jGraphStore(openNeo4j(cfg));
    }

    /**
     * Backend selection precedence:
     * <ol>
     *   <li>{@code cvector.embedded=true} (legacy override — still wins).</li>
     *   <li>{@code CVECTOR_EMBEDDED=true} env var.</li>
     *   <li>{@code cvector.backend} system property ({@code embedded} | {@code remote} | {@code docker}).</li>
     *   <li>{@code settings.json} backend field.</li>
     *   <li>Default: {@code embedded}.</li>
     * </ol>
     */
    public static String resolveBackend(CvectorConfig cfg) {
        if (Boolean.getBoolean("cvector.embedded")) return CvectorConfig.BACKEND_EMBEDDED;
        String env = System.getenv("CVECTOR_EMBEDDED");
        if (env != null && env.equalsIgnoreCase("true")) return CvectorConfig.BACKEND_EMBEDDED;
        String override = System.getProperty("cvector.backend");
        if (override != null && !override.isBlank()) return override.toLowerCase();
        return cfg == null ? CvectorConfig.BACKEND_EMBEDDED : cfg.backendOrDefault();
    }

    /**
     * Back-compat shim for callers that pre-date the backend field. Returns true when the
     * effective backend is {@code embedded}.
     */
    public static boolean isEmbeddedRequested() {
        if (Boolean.getBoolean("cvector.embedded")) return true;
        String env = System.getenv("CVECTOR_EMBEDDED");
        if (env != null && env.equalsIgnoreCase("true")) return true;
        String override = System.getProperty("cvector.backend");
        return override == null || override.isBlank() || override.equalsIgnoreCase(CvectorConfig.BACKEND_EMBEDDED);
    }

    public ProjectContext projectContext(CvectorConfig cfg) {
        CvectorConfig.ProjectEntry entry = requireActiveProject(cfg);
        Path root = entry.rootPath() == null ? resolveConfigRoot() : Paths.get(entry.rootPath());
        return new ProjectContext(entry.projectId(), entry.name(), root);
    }
}
