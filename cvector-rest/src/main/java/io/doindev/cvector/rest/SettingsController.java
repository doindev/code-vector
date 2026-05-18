package io.doindev.cvector.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read + write the workspace settings.json. {@code GET} masks secrets (neo4j.password) so the
 * dashboard can render the full config without leaking the credential over an unauthenticated
 * channel. {@code PUT} accepts a partial patch and merges into the on-disk file via
 * {@link CvectorConfigService#update(Path, java.util.function.Function)} so concurrent edits
 * don't trample unrelated sections.
 *
 * <p>{@link SettingsChangedEvent} is published after each successful write so other beans can
 * react (cache invalidation, "restart required" banners, etc).
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class SettingsController {

    /** What the dashboard renders in place of the real neo4j password. */
    public static final String PASSWORD_MASK = "***";

    private final CvectorConfigService configService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public SettingsController(CvectorConfigService configService, ApplicationEventPublisher events) {
        this.configService = configService;
        this.events = events;
    }

    @GetMapping("/settings")
    public Map<String, Object> get() throws IOException {
        Path root = resolveRoot();
        CvectorConfig cfg = configService.load(root);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", configService.configPath(root).toString());
        out.put("activeProject", cfg.activeProject());
        out.put("projects", cfg.projects());
        out.put("backend", cfg.backendOrDefault());
        out.put("rest", cfg.restOrDefault());
        out.put("mcp", cfg.mcpOrDefault());
        out.put("docker", cfg.dockerOrDefault());
        out.put("neo4j", maskNeo4j(cfg.neo4j()));
        return out;
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> put(@RequestBody Map<String, Object> patch) throws IOException {
        Path root = resolveRoot();
        CvectorConfig updated = configService.update(root, before -> applyPatch(before, patch));
        events.publishEvent(new SettingsChangedEvent("rest"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", configService.configPath(root).toString());
        out.put("activeProject", updated.activeProject());
        out.put("backend", updated.backendOrDefault());
        out.put("rest", updated.restOrDefault());
        out.put("mcp", updated.mcpOrDefault());
        out.put("docker", updated.dockerOrDefault());
        out.put("neo4j", maskNeo4j(updated.neo4j()));
        out.put("restartRequired", needsRestart(patch));
        return ResponseEntity.ok(out);
    }

    /**
     * Merge a partial patch into the existing config. Only sections explicitly present in
     * the patch are touched — a missing key means "leave as-is". For neo4j.password specifically,
     * a value of {@link #PASSWORD_MASK} or null preserves the existing password (so a UI that
     * round-trips the masked GET response doesn't accidentally erase the real credential).
     */
    @SuppressWarnings("unchecked")
    private CvectorConfig applyPatch(CvectorConfig before, Map<String, Object> patch) {
        Map<String, Object> p = patch == null ? Map.of() : patch;
        String activeProject = p.containsKey("activeProject")
                ? (String) p.get("activeProject")
                : before.activeProject();
        String backend = before.backend();
        if (p.containsKey("backend")) {
            String b = String.valueOf(p.get("backend")).toLowerCase();
            backend = switch (b) {
                case CvectorConfig.BACKEND_EMBEDDED, CvectorConfig.BACKEND_REMOTE, CvectorConfig.BACKEND_DOCKER -> b;
                default -> backend;
            };
        }
        CvectorConfig.RestConfig rest = before.rest();
        if (p.get("rest") instanceof Map<?, ?> r) {
            Integer port = toInt(r.get("port"), before.restOrDefault().port());
            Object hostObj = r.get("host");
            String host = hostObj == null ? before.restOrDefault().host() : String.valueOf(hostObj);
            rest = new CvectorConfig.RestConfig(port, host);
        }
        CvectorConfig.McpConfig mcp = before.mcp();
        if (p.get("mcp") instanceof Map<?, ?> m) {
            Object urlObj = m.get("url");
            Object tObj = m.get("transport");
            String url = urlObj == null ? before.mcpOrDefault().url() : String.valueOf(urlObj);
            String transport = tObj == null
                    ? before.mcpOrDefault().transport()
                    : CvectorConfig.McpConfig.canonicalTransport(String.valueOf(tObj));
            if (!CvectorConfig.McpConfig.isValidTransport(transport)) {
                transport = before.mcpOrDefault().transport();
            }
            CvectorConfig.McpConfig.McpTimeouts timeouts = mcp != null ? mcp.timeouts() : null;
            if (m.get("timeouts") instanceof Map<?, ?> t) {
                // Each field is optional and falls back to the prior persisted value when the
                // PATCH omits it — lets the dashboard send "just requestTimeoutMs" without
                // clobbering the other two.
                Long requestTimeoutMs = toLong(t.get("requestTimeoutMs"),
                        timeouts != null ? timeouts.requestTimeoutMs() : null);
                Long keepAliveIntervalMs = toLong(t.get("keepAliveIntervalMs"),
                        timeouts != null ? timeouts.keepAliveIntervalMs() : null);
                Long asyncRequestTimeoutMs = toLong(t.get("asyncRequestTimeoutMs"),
                        timeouts != null ? timeouts.asyncRequestTimeoutMs() : null);
                timeouts = new CvectorConfig.McpConfig.McpTimeouts(
                        requestTimeoutMs, keepAliveIntervalMs, asyncRequestTimeoutMs);
            }
            mcp = new CvectorConfig.McpConfig(url, transport, timeouts);
        }
        CvectorConfig.DockerConfig docker = before.docker();
        if (p.get("docker") instanceof Map<?, ?> d) {
            CvectorConfig.DockerConfig cur = before.dockerOrDefault();
            docker = new CvectorConfig.DockerConfig(
                    strOr(d.get("image"), cur.image()),
                    strOr(d.get("containerName"), cur.containerName()),
                    strOr(d.get("neo4jVersion"), cur.neo4jVersion()),
                    toInt(d.get("boltPort"), cur.boltPort()),
                    toInt(d.get("httpPort"), cur.httpPort()));
        }
        CvectorConfig.Neo4jConfig neo = before.neo4j();
        if (p.get("neo4j") instanceof Map<?, ?> n) {
            CvectorConfig.Neo4jConfig cur = neo != null ? neo : CvectorConfig.Neo4jConfig.defaults();
            String uri = strOr(n.get("uri"), cur.uri());
            String user = strOr(n.get("user"), cur.user());
            Object passObj = n.get("password");
            String password = (passObj == null || PASSWORD_MASK.equals(passObj))
                    ? cur.password()
                    : String.valueOf(passObj);
            neo = new CvectorConfig.Neo4jConfig(uri, user, password);
        }
        return new CvectorConfig(activeProject, before.projects(), neo, backend, rest, mcp, docker, before.rules(), before.kuzu());
    }

    private static boolean needsRestart(Map<String, Object> patch) {
        if (patch == null) return false;
        return patch.containsKey("backend") || patch.containsKey("rest")
                || patch.containsKey("mcp") || patch.containsKey("docker")
                || patch.containsKey("neo4j");
    }

    private static Map<String, Object> maskNeo4j(CvectorConfig.Neo4jConfig n) {
        if (n == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uri", n.uri());
        out.put("user", n.user());
        out.put("password", n.password() == null || n.password().isEmpty() ? "" : PASSWORD_MASK);
        return out;
    }

    private static Integer toInt(Object o, Integer fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    /**
     * Coerce a JSON number / numeric string into a {@code Long}. Returns {@code fallback}
     * (which may itself be {@code null}, signalling "no override") for missing or
     * unparseable input. Used by the mcp.timeouts PATCH path where every field is
     * optional and {@code null} explicitly means "let the application default stand".
     */
    private static Long toLong(Object o, Long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return fallback;
            try { return Long.parseLong(trimmed); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static String strOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o);
        return s.isBlank() ? fallback : s;
    }

    private Path resolveRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException("No .cvector/settings.json (or legacy project.json) under " + cwd);
        }
        return root;
    }
}
