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
        // One-shot override: a {@code --project <name|uuid|rootPath>} root-level flag (or
        // any caller that set {@code cvector.project} as a system property) takes priority
        // over the persistent activeProject field. Lets a user run e.g.
        // {@code cvector --project frontend search Foo} without committing the switch to
        // settings.json. Mirrors the MCP tools' optional-project-arg behaviour.
        String override = System.getProperty("cvector.project");
        if (override != null && !override.isBlank()) {
            CvectorConfig.ProjectEntry hit = resolveProjectOverride(cfg, override.trim());
            if (hit != null) return hit;
            throw new IllegalArgumentException(
                    "--project '" + override + "' did not match any project in settings.json. "
                            + "Valid projects: " + String.join(", ", cfg.projects().keySet())
                            + ". (Tools accept name, UUID, or absolute rootPath.)");
        }
        if (cfg.activeProject() == null) {
            throw new IllegalStateException("No active project. Use `cvector project switch <name>` or set one.");
        }
        CvectorConfig.ProjectEntry entry = cfg.projects().get(cfg.activeProject());
        if (entry == null) {
            throw new IllegalStateException("Active project '" + cfg.activeProject() + "' not in config.projects map.");
        }
        return entry;
    }

    /**
     * Resolve a one-shot project override (--project flag value) against the workspace.
     * Tries name → UUID → exact rootPath → ancestor-rootPath match in order. Returns
     * {@code null} when nothing matches; the caller throws with a list of candidates.
     */
    private static CvectorConfig.ProjectEntry resolveProjectOverride(CvectorConfig cfg, String input) {
        if (cfg.projects().containsKey(input)) return cfg.projects().get(input);
        for (CvectorConfig.ProjectEntry e : cfg.projects().values()) {
            if (input.equals(e.projectId())) return e;
        }
        // Path matching is gated on the input being unambiguously absolute. A bare
        // identifier like "does-not-exist" would otherwise get joined with the JVM's CWD
        // by Paths.get(...).toAbsolutePath() and accidentally resolve to whichever
        // registered project's rootPath is the CWD's ancestor. Mirrors the same guard in
        // ProjectResolver.findInternal — see the comment there for the failure mode.
        if (!isAbsolutePathInput(input)) return null;
        Path candidate = tryNormalize(input);
        if (candidate != null) {
            CvectorConfig.ProjectEntry ancestor = null;
            int bestDepth = Integer.MAX_VALUE;
            for (CvectorConfig.ProjectEntry e : cfg.projects().values()) {
                if (e.rootPath() == null) continue;
                Path existing = tryNormalize(e.rootPath());
                if (existing == null) continue;
                if (candidate.equals(existing)) return e;
                if (candidate.startsWith(existing)) {
                    int depth = candidate.getNameCount() - existing.getNameCount();
                    if (depth >= 0 && depth < bestDepth) {
                        bestDepth = depth;
                        ancestor = e;
                    }
                }
            }
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    private static boolean isAbsolutePathInput(String input) {
        if (input == null || input.isEmpty()) return false;
        char c0 = input.charAt(0);
        if (c0 == '/' || c0 == '\\') return true;
        if (input.length() >= 2 && Character.isLetter(c0) && input.charAt(1) == ':') return true;
        return false;
    }

    private static Path tryNormalize(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            Path p = Paths.get(s).toAbsolutePath().normalize();
            try { return p.toRealPath(); }
            catch (IOException ignored) { return p; }
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public Neo4jClient openNeo4j(CvectorConfig cfg) {
        CvectorConfig.Neo4jConfig n = cfg.neo4jOrDefault();
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
            Path db = EmbeddedKuzu.defaultDbPath(cfg, entry.projectId());
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
     *
     * <p>Earlier this method only consulted the legacy {@code -Dcvector.backend} system
     * property and defaulted to {@code true} when nothing was set — which meant
     * {@code "backend": "remote"} in {@code settings.json} was silently ignored by
     * {@code cvector scan}, {@code cvector diff}, and the dashboard banner. Now it
     * delegates to {@link #resolveBackend} which DOES read the loaded config, falling
     * back through the same precedence chain (system property → env → settings.json →
     * default embedded).
     */
    public static boolean isEmbeddedRequested(CvectorConfig cfg) {
        return CvectorConfig.BACKEND_EMBEDDED.equals(resolveBackend(cfg));
    }

    /**
     * Legacy no-arg variant — retained so callers that genuinely have no {@link CvectorConfig}
     * loaded (e.g. very early bootstrap) keep working, but they get only the system-property
     * / env-var precedence and default to embedded. Prefer the {@code (cfg)} overload.
     */
    public static boolean isEmbeddedRequested() {
        return isEmbeddedRequested(null);
    }

    public ProjectContext projectContext(CvectorConfig cfg) {
        CvectorConfig.ProjectEntry entry = requireActiveProject(cfg);
        Path root = entry.rootPath() == null ? resolveConfigRoot() : Paths.get(entry.rootPath());
        return new ProjectContext(entry.projectId(), entry.name(), root);
    }
}
