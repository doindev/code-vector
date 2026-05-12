package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@Command(name = "init", description = "Initialize cvector in the current directory.")
public class InitCommand implements Callable<Integer> {

    private static final String COMPOSE_TEMPLATE = """
            services:
              neo4j:
                image: neo4j:5
                container_name: cvector-neo4j
                ports:
                  - "7474:7474"
                  - "7687:7687"
                environment:
                  NEO4J_AUTH: neo4j/neo4jneo4j
                  NEO4J_dbms_memory_heap_initial__size: 512m
                  NEO4J_dbms_memory_heap_max__size: 2G
                volumes:
                  - cvector_neo4j_data:/data
            volumes:
              cvector_neo4j_data:
            """;

    @Option(names = "--project", description = "Project name (defaults to current directory name).")
    private String projectName;

    @Option(names = "--force", description = "Overwrite existing config.")
    private boolean force;

    private final CvectorRuntime runtime;

    public InitCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws IOException {
        Path cwd = runtime.workingDir();
        CvectorConfigService svc = runtime.configService();
        if (svc.exists(cwd) && !force) {
            System.err.println("config already exists at " + svc.configPath(cwd) + " (use --force to overwrite)");
            return 1;
        }
        String name = projectName != null ? projectName : cwd.getFileName().toString();
        String id = UUID.randomUUID().toString();
        Map<String, CvectorConfig.ProjectEntry> projects = new LinkedHashMap<>();
        projects.put(name, new CvectorConfig.ProjectEntry(id, name, cwd.toString()));
        CvectorConfig cfg = new CvectorConfig(name, projects, CvectorConfig.Neo4jConfig.defaults());
        svc.save(cwd, cfg);

        Path compose = svc.configDir(cwd).resolve("docker-compose.yml");
        if (!Files.exists(compose) || force) {
            Files.writeString(compose, COMPOSE_TEMPLATE);
        }

        System.out.println("initialized cvector project '" + name + "' (" + id + ")");
        System.out.println("  config:  " + svc.configPath(cwd));
        System.out.println("  compose: " + compose);
        System.out.println("next: `docker compose -f .cvector/docker-compose.yml up -d` then `cvector doctor`");
        return 0;
    }
}
