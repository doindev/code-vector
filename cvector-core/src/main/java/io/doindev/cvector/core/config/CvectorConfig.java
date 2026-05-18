package io.doindev.cvector.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted cvector workspace configuration — written to {@code .cvector/settings.json} (legacy
 * name {@code project.json} is still read as a fallback).
 *
 * <p>The schema is grown-by-section to keep migrations cheap: any field absent in the JSON
 * resolves to its {@code …Config.defaults()} on load, so old config files keep working when new
 * sections are added. Jackson is configured at the service layer to ignore unknown properties,
 * so the inverse — newer files read by older binaries — also doesn't fail loudly.
 *
 * <p>Default values intentionally live on the section records (not on {@code CvectorConfig}
 * itself) so a config that omits a section produces the same result as a config with an empty
 * section — no surprise nulls inside the runtime.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvectorConfig(
        String activeProject,
        Map<String, ProjectEntry> projects,
        Neo4jConfig neo4j,
        String backend,
        RestConfig rest,
        McpConfig mcp,
        DockerConfig docker,
        RulesPolicy rules,
        KuzuConfig kuzu
) {

    public CvectorConfig {
        if (projects == null) projects = new LinkedHashMap<>();
    }

    /**
     * Legacy 7-arg constructor for callers written before the per-workspace {@code rules}
     * and {@code kuzu} sections existed. Both fields default to {@code null}, which causes
     * the resolvers to fall through to defaults / rules.yml / per-project overrides / RAM-
     * based auto-sizing.
     */
    public CvectorConfig(String activeProject,
                         Map<String, ProjectEntry> projects,
                         Neo4jConfig neo4j,
                         String backend,
                         RestConfig rest,
                         McpConfig mcp,
                         DockerConfig docker) {
        this(activeProject, projects, neo4j, backend, rest, mcp, docker, null, null);
    }

    /** 8-arg constructor for callers that carry {@code rules} but not {@code kuzu}. */
    public CvectorConfig(String activeProject,
                         Map<String, ProjectEntry> projects,
                         Neo4jConfig neo4j,
                         String backend,
                         RestConfig rest,
                         McpConfig mcp,
                         DockerConfig docker,
                         RulesPolicy rules) {
        this(activeProject, projects, neo4j, backend, rest, mcp, docker, rules, null);
    }

    /** Backend mode: {@code embedded} (default), {@code remote}, or {@code docker}. */
    public static final String BACKEND_EMBEDDED = "embedded";
    public static final String BACKEND_REMOTE = "remote";
    public static final String BACKEND_DOCKER = "docker";

    /** Returns the configured backend mode, falling back to embedded when absent / blank. */
    public String backendOrDefault() {
        if (backend == null || backend.isBlank()) return BACKEND_EMBEDDED;
        String norm = backend.trim().toLowerCase();
        return switch (norm) {
            case BACKEND_EMBEDDED, BACKEND_REMOTE, BACKEND_DOCKER -> norm;
            default -> BACKEND_EMBEDDED;
        };
    }

    /** REST section with defaults applied where absent. */
    public RestConfig restOrDefault() {
        return rest != null ? rest.withDefaults() : RestConfig.defaults();
    }

    /** Neo4j section with defaults applied where absent — embedded workspaces omit the section entirely. */
    public Neo4jConfig neo4jOrDefault() {
        return neo4j != null ? neo4j : Neo4jConfig.defaults();
    }

    /** MCP section with defaults applied where absent. */
    public McpConfig mcpOrDefault() {
        return mcp != null ? mcp.withDefaults() : McpConfig.defaults();
    }

    /** Docker section with defaults applied where absent. */
    public DockerConfig dockerOrDefault() {
        return docker != null ? docker.withDefaults() : DockerConfig.defaults();
    }

    /** Kuzu section with defaults applied where absent — the section is entirely optional. */
    public KuzuConfig kuzuOrDefault() {
        return kuzu != null ? kuzu : new KuzuConfig(null, null);
    }

    /**
     * Whether the given project should use the shared Kuzu DB layout or its own directory.
     * Shared mode is the default unless either (a) {@code kuzu.sharedDb} is explicitly
     * {@code false} workspace-wide or (b) the project entry sets {@code isolated: true}.
     *
     * <p>The lookup tolerates an unknown {@code projectId} (returns the workspace-level
     * default) so freshly-created projects that haven't been added to {@code projects}
     * yet still get sensible routing.
     */
    public boolean isSharedDbMode(String projectId) {
        if (!kuzuOrDefault().sharedDbOrDefault()) return false;
        if (projectId == null) return true;
        for (ProjectEntry e : projects.values()) {
            if (projectId.equals(e.projectId())) return !e.isolatedOrDefault();
        }
        return true;
    }

    public ProjectEntry active() {
        if (activeProject == null) return null;
        return projects.get(activeProject);
    }

    /**
     * Per-workspace project entry. The optional {@code rules} field carries a project-scoped
     * override of the workspace-wide {@link RulesPolicy}; the rules engine merges both at
     * load time (defaults → rules.yml → workspace {@code rules} → project {@code rules}).
     *
     * <p>{@code @JsonInclude(NON_NULL)} keeps {@code rules: null} out of serialized files —
     * the auto-bootstrapped {@code ~/.cvector/settings.json} would otherwise carry a noisy
     * null field that confuses new users reading their first config file.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectEntry(String projectId, String name, String rootPath, RulesPolicy rules, Boolean isolated) {
        /** Legacy 3-arg constructor for callers that don't carry rules. */
        public ProjectEntry(String projectId, String name, String rootPath) {
            this(projectId, name, rootPath, null, null);
        }

        /** 4-arg constructor for callers that carry rules but not the isolated flag. */
        public ProjectEntry(String projectId, String name, String rootPath, RulesPolicy rules) {
            this(projectId, name, rootPath, rules, null);
        }

        /**
         * When {@code true}, this project's Kuzu graph lives in its own directory at
         * {@code ~/.cvector/kuzu-data/<projectId>/} (the pre-0.2.0 layout) instead of
         * sharing the workspace-level {@code ~/.cvector/kuzu-data/graph.kuzu/} with other
         * projects. Useful for very large projects you want isolated from the shared
         * file-lock contention, or CI workflows running parallel scans.
         *
         * <p>{@code null} is equivalent to {@code false}.
         */
        @JsonIgnore  // Jackson's is-prefix bean introspection would otherwise expose this as
                    // a serialised property called "olatedOrDefault" (stripping the leading
                    // "is"), polluting every settings.json write.
        public boolean isolatedOrDefault() {
            return Boolean.TRUE.equals(isolated);
        }
    }

    /**
     * Architecture-rules policy. Layered between the built-in defaults, an optional
     * {@code .cvector/rules.yml}, the workspace {@code rules} section, and any per-project
     * override on {@link ProjectEntry#rules}. Every field is optional — a {@code null} value
     * means "fall through to the next layer".
     *
     * <p>Resolution semantics ({@code RulesConfigResolver}):
     * <ul>
     *   <li>{@code thresholds}: map-merged — later layers override the same key.</li>
     *   <li>{@code disable}: union — any layer that lists a rule disables it.</li>
     *   <li>{@code excludePaths}: union — concatenated across layers, deduped.</li>
     *   <li>{@code custom}: by-name override — a per-project custom rule with the same name
     *       replaces a workspace-level one (Cypher + severity + description all updated).</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RulesPolicy(
            Map<String, Integer> thresholds,
            List<String> disable,
            List<String> excludePaths,
            List<CustomPolicy> custom
    ) {
        public static RulesPolicy empty() {
            return new RulesPolicy(null, null, null, null);
        }
    }

    /** Custom Cypher rule definition that can appear under {@link RulesPolicy#custom}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomPolicy(
            String name,
            String description,
            String severity,
            String cypher,
            String cypherKuzu
    ) {}

    /**
     * Embedded KuzuDB tuning knobs. All fields optional — when absent, cvector's defaults
     * apply (auto-sized buffer pool from system RAM, see {@code EmbeddedKuzu.resolveBufferSize}).
     *
     * <p>{@code bufferSizeMb} is the most common tuning: large projects on a small buffer
     * pool can hit {@code Buffer manager exception: Unable to allocate memory!} during the
     * bulk-load COPY phase. Setting this in {@code settings.json} pins the pool size for
     * the workspace so the user doesn't have to remember the system-property override on
     * every invocation. Kuzu's pool is fixed at database-open time and cannot grow at
     * runtime — pick a size up front, restart cvector if you change it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KuzuConfig(Integer bufferSizeMb, Boolean sharedDb) {
        /** Legacy 1-arg constructor for callers that don't carry the sharedDb flag. */
        public KuzuConfig(Integer bufferSizeMb) {
            this(bufferSizeMb, null);
        }

        /**
         * When {@code true} (the default for new installs as of 0.2.0), all non-isolated
         * projects share a single Kuzu database at {@code ~/.cvector/kuzu-data/graph.kuzu/}
         * partitioned by node {@code projectId}. Enables zero-cost project switching and
         * native cross-project queries.
         *
         * <p>When {@code false}, each project gets its own directory at
         * {@code ~/.cvector/kuzu-data/<projectId>/graph.kuzu/} (the pre-0.2.0 layout).
         * Use this if you run parallel scans across projects and the shared file-lock
         * contention hurts.
         *
         * <p>{@code null} is treated as {@code true} so upgrading users get the new
         * topology automatically. Per-project opt-out is via
         * {@link ProjectEntry#isolated}.
         */
        @JsonIgnore  // same Jackson is-prefix concern as ProjectEntry.isolatedOrDefault.
        public boolean sharedDbOrDefault() {
            return sharedDb == null || Boolean.TRUE.equals(sharedDb);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Neo4jConfig(String uri, String user, String password) {

        public static Neo4jConfig defaults() {
            return new Neo4jConfig("bolt://localhost:7687", "neo4j", "neo4j");
        }
    }

    /**
     * REST API + dashboard listener config. {@code host = 127.0.0.1} keeps the server bound to
     * localhost so the API isn't exposed without the user explicitly opting in via
     * {@code cvector host 0.0.0.0}.
     *
     * <p>{@code port} and {@code host} are shortcuts for {@code server.port} and
     * {@code server.address}. The free-form {@code server} map carries any other Spring Boot
     * {@code server.*} property the user wants to pin in {@code settings.json} — for example
     * {@code server.ssl.enabled}, {@code server.compression.*}, {@code server.servlet.session.*}.
     * Nested objects are flattened to dotted keys by {@code CvectorApplication.main}, then
     * promoted to {@code --server.<key>=<value>} command-line args so they sit at the top of
     * Spring Boot's property-source precedence and override any matching environment variable.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RestConfig(Integer port, String host, Map<String, Object> server) {

        /** Legacy 2-arg constructor for callers / config files written before the {@code server} map existed. */
        public RestConfig(Integer port, String host) {
            this(port, host, null);
        }

        public static RestConfig defaults() {
            return new RestConfig(2969, "127.0.0.1", null);
        }

        public RestConfig withDefaults() {
            return new RestConfig(
                    port == null ? 2969 : port,
                    host == null || host.isBlank() ? "127.0.0.1" : host,
                    server);
        }
    }

    /**
     * MCP server config. {@code transport} ∈ {@code sse} | {@code stdio} | {@code http}.
     * <p>{@code sse} is the default when co-hosted with the dashboard: Spring AI 1.0.0's
     * MCP server only implements the SSE transport (client opens {@code GET /sse},
     * server emits an {@code endpoint} event with a {@code /mcp?sessionId=…} URL,
     * client POSTs JSON-RPC there). The newer "Streamable HTTP" single-endpoint
     * transport from MCP spec 2024-11-05+ is not yet supported by Spring AI; the
     * server treats {@code http} as an alias for {@code sse} so existing config files
     * keep working, but {@code sse} is the accurate label.
     * <p>{@code url} is informational for clients — the server still binds to
     * {@link RestConfig#host} and {@link RestConfig#port} regardless.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpConfig(String url, String transport) {

        /**
         * MCP 2025-03-26 "Streamable HTTP" transport. Single endpoint (default {@code /mcp}),
         * session via {@code Mcp-Session-Id} header, optional SSE stream-back. The canonical
         * user-facing label for the modern HTTP MCP protocol — what {@code settings.json}
         * stores, what the dashboard UI shows, what the CLI accepts. Default for new installs.
         * Modern MCP clients (Eclipse Copilot, MCP Inspector v2, newer Claude integrations)
         * speak this protocol.
         */
        public static final String TRANSPORT_HTTP = "http";
        /**
         * Legacy alias for {@link #TRANSPORT_HTTP}. Briefly written into some settings.json
         * files during the Spring AI 2.0 transition; {@link #canonicalTransport} folds it
         * back into {@code "http"} on read so the dashboard / CLI / server all see one
         * canonical value.
         */
        public static final String TRANSPORT_STREAMABLE = "streamable";
        /**
         * MCP 2024-11-05 "HTTP+SSE" transport. Two endpoints: GET {@code /sse} for the long-
         * lived SSE stream, POST {@code /mcp/message?sessionId=…} for JSON-RPC. Kept for
         * back-compat with older clients (original Claude Desktop, early MCP Inspector).
         */
        public static final String TRANSPORT_SSE = "sse";
        /**
         * stdio transport — JSON-RPC over the subprocess's stdin/stdout. Used by Claude CLI
         * and by clients that spawn cvector via {@code cvector serve}. Mutually exclusive
         * with HTTP transports in a single JVM (you'd race the dashboard for stdin).
         */
        public static final String TRANSPORT_STDIO = "stdio";

        private static final String DEFAULT_URL = "http://127.0.0.1:2969/mcp";

        public static McpConfig defaults() {
            return new McpConfig(DEFAULT_URL, TRANSPORT_HTTP);
        }

        public McpConfig withDefaults() {
            return new McpConfig(
                    url == null || url.isBlank() ? DEFAULT_URL : url,
                    transport == null || transport.isBlank() ? TRANSPORT_HTTP : canonicalTransport(transport));
        }

        /** Validates the transport string is one of the supported values. */
        public static boolean isValidTransport(String t) {
            if (t == null) return false;
            return switch (t.toLowerCase()) {
                case TRANSPORT_HTTP, TRANSPORT_STREAMABLE, TRANSPORT_SSE, TRANSPORT_STDIO -> true;
                default -> false;
            };
        }

        /**
         * Collapses the {@code streamable} legacy alias into {@code http} and lower-cases
         * the result. The user-facing schema accepts three values — {@code http} / {@code sse}
         * / {@code stdio} — and {@code streamable} folds into {@code http} so the rest of
         * the codebase sees a single canonical name. Anything not in the known set is
         * returned as-is so callers can still surface a useful error message — pair with
         * {@link #isValidTransport} for guarding.
         */
        public static String canonicalTransport(String t) {
            if (t == null) return null;
            String lower = t.toLowerCase();
            return TRANSPORT_STREAMABLE.equals(lower) ? TRANSPORT_HTTP : lower;
        }
    }

    /**
     * Docker-managed Neo4j config used when {@link #backend} is {@code docker}. The compose file
     * lives at {@code .cvector/docker-compose.yml} and {@code cvector init} writes it on demand.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DockerConfig(String image, String containerName, String neo4jVersion, Integer boltPort, Integer httpPort) {

        public static DockerConfig defaults() {
            return new DockerConfig("neo4j", "cvector-neo4j", "5", 7687, 7474);
        }

        public DockerConfig withDefaults() {
            return new DockerConfig(
                    image == null || image.isBlank() ? "neo4j" : image,
                    containerName == null || containerName.isBlank() ? "cvector-neo4j" : containerName,
                    neo4jVersion == null || neo4jVersion.isBlank() ? "5" : neo4jVersion,
                    boltPort == null ? 7687 : boltPort,
                    httpPort == null ? 7474 : httpPort);
        }
    }
}
