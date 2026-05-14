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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean(destroyMethod = "close")
    public GraphStore mcpGraphStore(CvectorConfigService configService, McpActiveProject project) {
        CvectorConfig cfg = loadConfig(configService);
        if (embeddedRequested()) {
            Path db = EmbeddedKuzu.defaultDbPath(project.projectId());
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

    @Bean
    public McpActiveProject mcpActiveProject(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        if (cfg.activeProject() == null || !cfg.projects().containsKey(cfg.activeProject())) {
            throw new IllegalStateException("No active project — run `cvector init` and `cvector scan` first.");
        }
        CvectorConfig.ProjectEntry e = cfg.projects().get(cfg.activeProject());
        return new McpActiveProject(e.projectId(), e.name(), e.rootPath());
    }

    @Bean
    public CvectorTools cvectorTools(GraphStore mcpGraphStore, McpActiveProject mcpActiveProject) {
        return new CvectorTools(mcpGraphStore, mcpActiveProject);
    }

    @Bean
    public CvectorRole cvectorRole() {
        return CvectorRole.resolve();
    }

    @Bean
    public CvectorResources cvectorResources(GraphStore mcpGraphStore, McpActiveProject mcpActiveProject) {
        return new CvectorResources(mcpGraphStore, mcpActiveProject);
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
            throw new IllegalStateException("No .cvector/settings.json (or legacy project.json) from "
                    + cwd + " — run `cvector init`.");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record McpActiveProject(String projectId, String name, String rootPath) {}
}
