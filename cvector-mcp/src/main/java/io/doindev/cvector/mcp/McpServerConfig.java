package io.doindev.cvector.mcp;

import io.doindev.cvector.core.CvectorRole;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuGraphStore;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.Neo4jGraphStore;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Wires the MCP server's beans against either Neo4j or the embedded KuzuDB store, controlled by
 * {@code cvector.embedded} / {@code CVECTOR_EMBEDDED}. The {@link GraphStore} bean owns the live
 * connection; {@link GraphQueries} is only wired for the Neo4j path (it backs the few legacy MCP
 * tools that still use raw Cypher with Neo4j-specific dialect).
 */
@Configuration
@Profile("mcp")
public class McpServerConfig {

    private static boolean embeddedRequested() {
        if (Boolean.getBoolean("cvector.embedded")) return true;
        String env = System.getenv("CVECTOR_EMBEDDED");
        return env != null && env.equalsIgnoreCase("true");
    }

    /**
     * Only created when no other {@link GraphStore} bean exists in the context. When the
     * {@code mcp} profile is co-active with the dashboard (e.g. {@code cvector dashboard}
     * also activates {@code mcp} so stdio MCP can run alongside REST), the rest module's
     * {@code restGraphStore} bean already owns the live Kuzu/Neo4j connection — reuse it
     * instead of opening a second one (Kuzu file-locks the DB directory, so a duplicate
     * open would crash startup).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(GraphStore.class)
    public GraphStore mcpGraphStore(CvectorConfigService configService, McpActiveProject project) {
        CvectorConfig cfg = loadConfig(configService);
        if (embeddedRequested()) {
            Path db = EmbeddedKuzu.defaultDbPath(cfg, project.projectId());
            try {
                EmbeddedKuzu kuzu = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg));
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                return new KuzuGraphStore(kuzu);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to open embedded kuzu at " + db, e);
            }
        }
        CvectorConfig.Neo4jConfig n = cfg.neo4jOrDefault();
        return new Neo4jGraphStore(new Neo4jClient(n.uri(), n.user(), n.password()));
    }

    /**
     * Resolves project name / UUID / path arguments supplied by MCP clients to canonical
     * {@link CvectorConfig.ProjectEntry} records. Single source of truth for "what is the
     * agent talking about" across every tool — the previous {@code McpActiveProject} bean
     * (single, frozen at boot) is gone in 0.2.0 because the API now requires tools to be
     * explicit about which project they target.
     */
    @Bean
    public ProjectResolver projectResolver(CvectorConfigService configService, GraphStore mcpGraphStore) {
        return new ProjectResolver(configService, mcpGraphStore);
    }

    /**
     * Back-compat shim for callers (notably {@link CvectorResources}) that still need
     * "the active project on boot" until they migrate to per-call projectId. New code
     * should depend on {@link ProjectResolver} instead.
     */
    @Bean
    public McpActiveProject mcpActiveProject(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        if (cfg.activeProject() == null || !cfg.projects().containsKey(cfg.activeProject())) {
            // Empty-workspace placeholder so `cvector serve` / `cvector dashboard` boot
            // without an existing project. The agent's first move can be cv_add_project
            // or cv_onboard_project — neither requires an existing active project.
            return new McpActiveProject(null, null, null);
        }
        CvectorConfig.ProjectEntry e = cfg.projects().get(cfg.activeProject());
        return new McpActiveProject(e.projectId(), e.name(), e.rootPath());
    }

    @Bean
    public CvectorTools cvectorTools(GraphStore graphStore, ProjectResolver projectResolver,
                                     CvectorScanService cvectorScanService) {
        return new CvectorTools(graphStore, projectResolver, cvectorScanService);
    }

    @Bean
    public CvectorRole cvectorRole() {
        return CvectorRole.resolve();
    }

    @Bean
    public CvectorResources cvectorResources(GraphStore graphStore, McpActiveProject mcpActiveProject) {
        return new CvectorResources(graphStore, mcpActiveProject);
    }

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> mcpResourceSpecifications(CvectorResources cvectorResources) {
        return cvectorResources.getResources();
    }

    @Bean
    public CvectorPrompts cvectorPrompts() {
        return new CvectorPrompts();
    }

    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> mcpPromptSpecifications(CvectorPrompts cvectorPrompts) {
        return cvectorPrompts.getPrompts();
    }

    @Bean
    public ToolCallbackProvider cvectorToolCallbacks(CvectorTools cvectorTools, CvectorRole cvectorRole) {
        ToolCallbackProvider inner = MethodToolCallbackProvider.builder()
                .toolObjects(cvectorTools)
                .build();
        return () -> {
            ToolCallback[] all = inner.getToolCallbacks();
            ToolCallback[] filtered = Arrays.stream(all)
                    .filter(cb -> cvectorRole.allowsTool(cb.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
            return filtered;
        };
    }

    private static CvectorConfig loadConfig(CvectorConfigService configService) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException("No .cvector/settings.json found from "
                    + cwd + " (also checked your user home directory). Run `cvector init`, or "
                    + "create a global workspace at ~/.cvector/settings.json.");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record McpActiveProject(String projectId, String name, String rootPath) {}

    /**
     * Forces {@link McpSyncServer} to be eagerly constructed at startup even when the
     * surrounding application has {@code spring.main.lazy-initialization=true}. Spring
     * AI's auto-config wires the transport provider's {@code sessionFactory} inside
     * {@code McpSyncServer}'s constructor, but no application code injects that bean
     * at runtime — under lazy init Spring would never instantiate it and the first
     * SSE request would NPE deep inside {@code WebMvcSseServerTransportProvider}.
     *
     * <p>This component depends-on (via constructor injection) the
     * {@code McpSyncServer} bean, which makes Spring eagerly create it during context
     * refresh regardless of the global lazy-init flag. {@code @Lazy(false)} on this
     * holder is belt-and-suspenders — Spring already eager-creates {@code @Component}s
     * that aren't explicitly {@code @Lazy}, but spelling it out makes the intent
     * survive future global-lazy refactors.
     *
     * <p>No {@code @ConditionalOnBean(McpSyncServer.class)} here on purpose: conditions
     * on {@code @Component} classes are evaluated during component scan, before Spring
     * AI's auto-config has registered the {@code McpSyncServer} bean definition. With
     * the conditional, the holder gets skipped and lazy-init then leaves
     * {@code McpSyncServer} uncreated. The enclosing {@code @Profile("mcp")} +
     * {@code spring.ai.mcp.server.enabled=true} in {@code application-mcp.properties}
     * already guarantee the bean exists whenever this holder runs.
     */
    @org.springframework.stereotype.Component
    @Lazy(false)
    @Profile("mcp")
    public static class McpSyncServerEagerInitializer {
        public McpSyncServerEagerInitializer(McpSyncServer mcpSyncServer) {
            // Intentionally empty — the constructor parameter alone is the trigger.
        }
    }

    /**
     * Allow cross-origin requests to the MCP HTTP/SSE endpoints. Browser-hosted MCP
     * clients (e.g. the MCP Inspector at {@code http://localhost:6274}) run on a
     * different origin than the cvector dashboard ({@code http://localhost:2969}) and
     * the browser will block the SSE handshake with an opaque "Failed to fetch" error
     * if the server doesn't send {@code Access-Control-Allow-Origin}.
     *
     * <p>We allow all origins on {@code /sse} and {@code /mcp}. The dashboard already
     * binds to loopback only by default ({@code rest.host=127.0.0.1}), so off-host
     * exposure requires the operator's explicit opt-in via {@code cvector host 0.0.0.0}.
     * CORS open on a loopback-only listener costs nothing security-wise — the only
     * callers are on the local machine.
     */
    @Bean
    public org.springframework.web.servlet.config.annotation.WebMvcConfigurer mcpCorsConfigurer() {
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override
            public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
                registry.addMapping("/sse").allowedOriginPatterns("*").allowedMethods("*").allowedHeaders("*");
                registry.addMapping("/mcp").allowedOriginPatterns("*").allowedMethods("*").allowedHeaders("*");
                registry.addMapping("/mcp/**").allowedOriginPatterns("*").allowedMethods("*").allowedHeaders("*");
            }
        };
    }
}
