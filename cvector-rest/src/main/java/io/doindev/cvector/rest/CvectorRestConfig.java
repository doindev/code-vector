package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@ConditionalOnWebApplication
public class CvectorRestConfig {

    @Bean(destroyMethod = "close")
    public Neo4jClient restNeo4jClient(CvectorConfigService configService) {
        CvectorConfig cfg = loadConfig(configService);
        CvectorConfig.Neo4jConfig n = cfg.neo4j() != null ? cfg.neo4j() : CvectorConfig.Neo4jConfig.defaults();
        return new Neo4jClient(n.uri(), n.user(), n.password());
    }

    @Bean
    public GraphQueries restGraphQueries(Neo4jClient restNeo4jClient) {
        return new GraphQueries(restNeo4jClient);
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
