package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates the runtime project switch:
 * <ol>
 *   <li>Validate the target project key against {@code .cvector/settings.json}.</li>
 *   <li>Persist the new {@code activeProject} into settings.json so a restart picks it up too.</li>
 *   <li>Call {@link GraphStore#swapToProject} so the embedded backend points at the new
 *       project's data files (no-op on Neo4j — projectId filter handles it per-query).</li>
 *   <li>Mutate the {@link ActiveProject} fields so every controller's next read sees the
 *       new project metadata.</li>
 *   <li>Publish {@link GraphMutatedEvent} so both cache layers (object + JSON byte[]) flush.</li>
 * </ol>
 *
 * <p>Single instance; {@link #switchTo} is synchronised so two concurrent calls don't
 * interleave (the swapLock inside {@code KuzuGraphStore} is the second line of defence).
 */
@Component
@ConditionalOnWebApplication
public class WorkspaceSwitcher {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSwitcher.class);

    private final CvectorConfigService configService;
    private final ActiveProject activeProject;
    private final GraphStore store;
    private final ApplicationEventPublisher events;

    public WorkspaceSwitcher(CvectorConfigService configService,
                             ActiveProject activeProject,
                             GraphStore restGraphStore,
                             ApplicationEventPublisher events) {
        this.configService = configService;
        this.activeProject = activeProject;
        this.store = restGraphStore;
        this.events = events;
    }

    public synchronized Map<String, Object> switchTo(String key) {
        if (key == null || key.isBlank()) {
            return Map.of("ok", false, "reason", "missing-key");
        }
        Path root = configRoot();
        if (root == null) {
            return Map.of("ok", false, "reason", "no-workspace-config");
        }
        CvectorConfig cfg;
        try {
            cfg = configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CvectorConfig.ProjectEntry target = cfg.projects().get(key);
        if (target == null) {
            return Map.of("ok", false, "reason", "unknown-project", "available", cfg.projects().keySet());
        }
        if (key.equals(cfg.activeProject())
                && target.projectId().equals(activeProject.projectId())) {
            return Map.of("ok", true, "noop", true, "active", snapshot(target));
        }

        // Persist first — if the in-memory swap throws, the next restart will still land on
        // the requested project (no half-state where settings.json says X but the live bean
        // still serves Y).
        try {
            configService.save(root, new CvectorConfig(
                    key, cfg.projects(), cfg.neo4j(),
                    cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker(), cfg.rules()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        log.info("switching active project: {} → {} (id={})",
                cfg.activeProject(), key, target.projectId());
        store.swapToProject(target.projectId());
        activeProject.replaceFields(target.projectId(), target.name(), target.rootPath());

        // Flush both cache layers so subsequent reads compute against the new project.
        events.publishEvent(GraphMutatedEvent.fromScan());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("noop", false);
        out.put("active", snapshot(target));
        return out;
    }

    private Map<String, Object> snapshot(CvectorConfig.ProjectEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", e.projectId());
        m.put("name", e.name());
        m.put("rootPath", e.rootPath());
        return m;
    }

    private Path configRoot() {
        try {
            return configService.findConfigRoot(Paths.get("").toAbsolutePath());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
