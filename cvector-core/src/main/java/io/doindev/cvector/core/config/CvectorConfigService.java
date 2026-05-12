package io.doindev.cvector.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

public class CvectorConfigService {

    public static final String CONFIG_DIR = ".cvector";
    public static final String CONFIG_FILE = "project.json";

    private final ObjectMapper mapper;

    public CvectorConfigService() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path configDir(Path projectRoot) {
        return projectRoot.resolve(CONFIG_DIR);
    }

    public Path configPath(Path projectRoot) {
        return configDir(projectRoot).resolve(CONFIG_FILE);
    }

    public boolean exists(Path projectRoot) {
        return Files.exists(configPath(projectRoot));
    }

    public CvectorConfig load(Path projectRoot) throws IOException {
        CvectorConfig raw = mapper.readValue(configPath(projectRoot).toFile(), CvectorConfig.class);
        if (raw.projects() == null) {
            return new CvectorConfig(raw.activeProject(), new LinkedHashMap<>(), raw.neo4j());
        }
        return raw;
    }

    public void save(Path projectRoot, CvectorConfig config) throws IOException {
        Path file = configPath(projectRoot);
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), config);
    }

    public Path findConfigRoot(Path start) {
        Path cur = start.toAbsolutePath().normalize();
        while (cur != null) {
            if (exists(cur)) return cur;
            cur = cur.getParent();
        }
        return null;
    }
}
