package io.doindev.cvector.mcp;

import io.doindev.cvector.core.CvectorRole;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
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

@Configuration
@Profile("mcp")
public class McpServerConfig {

    @Bean(destroyMethod = "close")
    public Neo4jClient mcpNeo4jClient(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        CvectorConfig.Neo4jConfig n = cfg.neo4j() != null ? cfg.neo4j() : CvectorConfig.Neo4jConfig.defaults();
        return new Neo4jClient(n.uri(), n.user(), n.password());
    }

    @Bean
    public GraphQueries mcpGraphQueries(Neo4jClient mcpNeo4jClient) {
        return new GraphQueries(mcpNeo4jClient);
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
    public CvectorTools cvectorTools(GraphQueries mcpGraphQueries, Neo4jClient mcpNeo4jClient, McpActiveProject mcpActiveProject) {
        return new CvectorTools(mcpGraphQueries, mcpNeo4jClient, mcpActiveProject);
    }

    @Bean
    public CvectorRole cvectorRole() {
        return CvectorRole.resolve();
    }

    @Bean
    public CvectorResources cvectorResources(GraphQueries mcpGraphQueries, McpActiveProject mcpActiveProject) {
        return new CvectorResources(mcpGraphQueries, mcpActiveProject);
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
            throw new IllegalStateException("No .cvector/project.json from " + cwd + " — run `cvector init`.");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record McpActiveProject(String projectId, String name, String rootPath) {}
}
