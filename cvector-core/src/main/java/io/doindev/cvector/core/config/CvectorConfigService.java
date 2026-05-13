package io.doindev.cvector.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Reads and writes the persistent cvector workspace config.
 *
 * <p>The canonical file name is {@code settings.json} under {@code .cvector/}. The legacy
 * {@code project.json} name is still read for backward compatibility — if a workspace has
 * {@code project.json} but not {@code settings.json}, the loader transparently picks it up so
 * existing projects keep booting after the upgrade. On the next {@code save()} we write
 * {@code settings.json}; we don't auto-delete the old file (let the user clean it up so a
 * downgrade still works).
 */
public class CvectorConfigService {

    public static final String CONFIG_DIR = ".cvector";
    /** Canonical config file name. */
    public static final String CONFIG_FILE = "settings.json";
    /** Legacy name still read from disk; never written. */
    public static final String LEGACY_CONFIG_FILE = "project.json";

    private final ObjectMapper mapper;

    public CvectorConfigService() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Tolerate unknown sections so newer/older versions can read the same file.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Path configDir(Path projectRoot) {
        return projectRoot.resolve(CONFIG_DIR);
    }

    /** Path we write to. Always {@code .cvector/settings.json}. */
    public Path configPath(Path projectRoot) {
        return configDir(projectRoot).resolve(CONFIG_FILE);
    }

    /** Path we read from — settings.json if present, otherwise the legacy project.json. */
    public Path readPath(Path projectRoot) {
        Path canonical = configPath(projectRoot);
        if (Files.exists(canonical)) return canonical;
        Path legacy = configDir(projectRoot).resolve(LEGACY_CONFIG_FILE);
        if (Files.exists(legacy)) return legacy;
        return canonical;
    }

    public boolean exists(Path projectRoot) {
        return Files.exists(configPath(projectRoot))
                || Files.exists(configDir(projectRoot).resolve(LEGACY_CONFIG_FILE));
    }

    public CvectorConfig load(Path projectRoot) throws IOException {
        CvectorConfig raw = mapper.readValue(readPath(projectRoot).toFile(), CvectorConfig.class);
        if (raw.projects() == null) {
            return new CvectorConfig(raw.activeProject(), new LinkedHashMap<>(),
                    raw.neo4j(), raw.backend(), raw.rest(), raw.mcp(), raw.docker());
        }
        return raw;
    }

    /**
     * Atomic per-section update: read the current config, apply {@code mutator}, save. Returns
     * the saved config so callers can echo the result. Used by the CLI commands that flip a
     * single field (e.g. {@code cvector host}, {@code cvector db --embedded}).
     */
    public CvectorConfig update(Path projectRoot, java.util.function.Function<CvectorConfig, CvectorConfig> mutator) throws IOException {
        CvectorConfig before = load(projectRoot);
        CvectorConfig after = mutator.apply(before);
        save(projectRoot, after);
        return after;
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
