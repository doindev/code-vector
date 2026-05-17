package io.doindev.cvector.cli.commands.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@Command(name = "create", description = "Create a new project in the workspace.", mixinStandardHelpOptions = true)
public class ProjectCreateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String name;

    @Option(names = "--root", description = "Project root path (defaults to current directory).")
    private Path root;

    @Option(names = "--switch", description = "Also switch the active project to the new one.")
    private boolean switchAfter;

    @Option(names = "--no-monitor",
            description = "Skip seeding a (paused) directory monitor for the new project root. Default: a monitor is added in disabled state so it shows up in the dashboard's Monitors view, ready to be toggled on later.")
    private boolean skipMonitor;

    @Option(names = "--isolated",
            description = "Give this project its own Kuzu DB directory at ~/.cvector/kuzu-data/<projectId>/ instead of sharing the workspace's shared DB. Useful for very large projects or CI workflows running parallel scans. Default: shared.")
    private boolean isolated;

    private final CvectorRuntime runtime;

    public ProjectCreateCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        Path configRoot = runtime.resolveConfigRoot();
        CvectorConfigService svc = runtime.configService();
        CvectorConfig cfg = svc.load(configRoot);

        if (cfg.projects().containsKey(name)) {
            System.err.println("project '" + name + "' already exists");
            return 1;
        }
        Path rootPath = root != null ? root.toAbsolutePath().normalize() : Paths.get(".").toAbsolutePath().normalize();
        String overlapError = findOverlap(cfg, rootPath);
        if (overlapError != null) {
            System.err.println(overlapError);
            return 1;
        }
        Map<String, CvectorConfig.ProjectEntry> projects = new LinkedHashMap<>(cfg.projects());
        String projectId = UUID.randomUUID().toString();
        Boolean isolatedFlag = isolated ? Boolean.TRUE : null;
        projects.put(name, new CvectorConfig.ProjectEntry(projectId, name, rootPath.toString(), null, isolatedFlag));

        String activeProject = switchAfter ? name : cfg.activeProject();
        CvectorConfig updated = new CvectorConfig(activeProject, projects, cfg.neo4j(),
                cfg.backend(), cfg.rest(), cfg.mcp(), cfg.docker(), cfg.rules(), cfg.kuzu());
        svc.save(configRoot, updated);

        System.out.println("created project '" + name + "' (" + projectId + ") at " + rootPath
                + (isolated ? " [isolated Kuzu DB]" : ""));
        if (switchAfter) System.out.println("active project is now '" + name + "'");

        if (!skipMonitor) {
            String monitorId = appendPausedMonitor(rootPath);
            if (monitorId != null) {
                System.out.println("seeded paused dashboard monitor (" + monitorId + ") for " + rootPath);
                System.out.println("  toggle it on later from /dashboard/monitors or via PATCH /api/dashboard/monitors/" + monitorId);
            }
        }
        return 0;
    }

    /**
     * Appends a {@code Monitor} entry to {@code ~/.cvector/dashboard.json} in disabled
     * state so the dashboard's Monitors view picks it up the next time the dashboard
     * starts. We intentionally write the JSON directly rather than depending on the
     * {@code cvector-dashboard} module from the CLI — the schema is small and stable,
     * and a hard dep would pull the rest of the web/MCP stack into one-shot CLI calls.
     *
     * <p>Returns the new monitor id on success, {@code null} on I/O failure (the
     * project itself is still created — monitor seeding is best-effort, not critical).
     * If the same path is already monitored, no new entry is added.
     */
    /**
     * Returns a descriptive error message if {@code rootPath} overlaps with an existing
     * project (equals it, is a descendant of it, or contains it), or {@code null} when the
     * path is safe to register. Mirrors {@code ProjectResolver.validateAddable} from the
     * MCP module — duplicated here so cvector-cli doesn't depend on cvector-mcp just for
     * this one check.
     */
    private static String findOverlap(CvectorConfig cfg, Path normalizedNewRoot) {
        Path candidate = tryRealOrNormalize(normalizedNewRoot);
        if (candidate == null) return "rootPath could not be resolved: " + normalizedNewRoot;
        for (CvectorConfig.ProjectEntry e : cfg.projects().values()) {
            if (e.rootPath() == null) continue;
            Path existing = tryRealOrNormalize(Paths.get(e.rootPath()));
            if (existing == null) continue;
            if (candidate.equals(existing)) {
                return "rootPath '" + normalizedNewRoot + "' is already registered as project '"
                        + e.name() + "' (" + e.projectId() + ").";
            }
            if (candidate.startsWith(existing)) {
                return "rootPath '" + normalizedNewRoot + "' is inside an existing project '"
                        + e.name() + "' (rooted at " + e.rootPath()
                        + "). Sub-directories of existing projects cannot be onboarded — query the parent project instead.";
            }
            if (existing.startsWith(candidate)) {
                return "rootPath '" + normalizedNewRoot + "' would contain an existing project '"
                        + e.name() + "' (rooted at " + e.rootPath()
                        + "). Onboarding a parent of an existing project is not allowed — remove the inner project first, or pick a non-overlapping rootPath.";
            }
        }
        return null;
    }

    private static Path tryRealOrNormalize(Path p) {
        if (p == null) return null;
        Path abs = p.toAbsolutePath().normalize();
        try { return abs.toRealPath(); }
        catch (java.io.IOException ignored) { return abs; }
    }

    private static String appendPausedMonitor(Path projectRoot) {
        Path storeFile = Paths.get(System.getProperty("user.home"), ".cvector", "dashboard.json");
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            ObjectNode state;
            if (Files.exists(storeFile) && Files.size(storeFile) > 0) {
                JsonNode loaded = mapper.readTree(storeFile.toFile());
                state = loaded.isObject() ? (ObjectNode) loaded : mapper.createObjectNode();
            } else {
                state = mapper.createObjectNode();
            }
            ArrayNode monitors = state.withArray("monitors");
            String pathStr = projectRoot.toString();
            for (JsonNode existing : monitors) {
                if (existing.path("path").asText("").equals(pathStr)) return null;
            }
            String monitorId = UUID.randomUUID().toString();
            ObjectNode monitor = mapper.createObjectNode();
            monitor.put("id", monitorId);
            monitor.put("path", pathStr);
            monitor.put("enabled", false);
            monitor.put("addedAt", Instant.now().toString());
            monitors.add(monitor);
            // Round-trip the other top-level arrays so re-loads by the dashboard get a
            // well-shaped object even on a freshly-seeded file.
            if (!state.has("schedules")) state.withArray("schedules");
            if (!state.has("queries")) state.withArray("queries");
            if (!state.has("settings")) state.set("settings", mapper.createObjectNode());

            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName().toString() + ".tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return monitorId;
        } catch (IOException e) {
            System.err.println("warning: could not seed dashboard monitor for " + projectRoot + ": " + e.getMessage());
            return null;
        }
    }
}
