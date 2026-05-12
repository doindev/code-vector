package io.doindev.cvector.cli;

import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.neo4j.Neo4jClient;
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
                    "No .cvector/project.json found in " + workingDir() + " or any parent. Run `cvector init` first.");
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

    public ProjectContext projectContext(CvectorConfig cfg) {
        CvectorConfig.ProjectEntry entry = requireActiveProject(cfg);
        Path root = entry.rootPath() == null ? resolveConfigRoot() : Paths.get(entry.rootPath());
        return new ProjectContext(entry.projectId(), entry.name(), root);
    }
}
