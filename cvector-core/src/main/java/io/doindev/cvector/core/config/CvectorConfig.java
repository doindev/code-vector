package io.doindev.cvector.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
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
        DockerConfig docker
) {

    public CvectorConfig {
        if (projects == null) projects = new LinkedHashMap<>();
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

    /** MCP section with defaults applied where absent. */
    public McpConfig mcpOrDefault() {
        return mcp != null ? mcp.withDefaults() : McpConfig.defaults();
    }

    /** Docker section with defaults applied where absent. */
    public DockerConfig dockerOrDefault() {
        return docker != null ? docker.withDefaults() : DockerConfig.defaults();
    }

    public ProjectEntry active() {
        if (activeProject == null) return null;
        return projects.get(activeProject);
    }

    public record ProjectEntry(String projectId, String name, String rootPath) {}

    public record Neo4jConfig(String uri, String user, String password) {

        public static Neo4jConfig defaults() {
            return new Neo4jConfig("bolt://localhost:7687", "neo4j", "neo4j");
        }
    }

    /**
     * REST API + dashboard listener config. {@code host = 127.0.0.1} keeps the server bound to
     * localhost so the API isn't exposed without the user explicitly opting in via
     * {@code cvector host 0.0.0.0}.
     */
    public record RestConfig(Integer port, String host) {

        public static RestConfig defaults() {
            return new RestConfig(2969, "127.0.0.1");
        }

        public RestConfig withDefaults() {
            return new RestConfig(
                    port == null ? 2969 : port,
                    host == null || host.isBlank() ? "127.0.0.1" : host);
        }
    }

    /**
     * MCP server config. {@code transport} ∈ {@code http} | {@code sse} | {@code stdio}.
     * {@code url} is informational for clients — the server still binds to {@link RestConfig#host}
     * and {@link RestConfig#port} (plus the MCP path) by default.
     */
    public record McpConfig(String url, String transport) {

        public static final String TRANSPORT_HTTP = "http";
        public static final String TRANSPORT_SSE = "sse";
        public static final String TRANSPORT_STDIO = "stdio";

        public static McpConfig defaults() {
            return new McpConfig("http://127.0.0.1:2969/mcp", TRANSPORT_HTTP);
        }

        public McpConfig withDefaults() {
            return new McpConfig(
                    url == null || url.isBlank() ? "http://127.0.0.1:2969/mcp" : url,
                    transport == null || transport.isBlank() ? TRANSPORT_HTTP : transport.toLowerCase());
        }

        /** Validates the transport string is one of the supported values. */
        public static boolean isValidTransport(String t) {
            if (t == null) return false;
            return switch (t.toLowerCase()) {
                case TRANSPORT_HTTP, TRANSPORT_SSE, TRANSPORT_STDIO -> true;
                default -> false;
            };
        }
    }

    /**
     * Docker-managed Neo4j config used when {@link #backend} is {@code docker}. The compose file
     * lives at {@code .cvector/docker-compose.yml} and {@code cvector init} writes it on demand.
     */
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
