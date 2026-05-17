package io.doindev.cvector.mcp;

import io.doindev.cvector.cli.scan.InProcessScanService;
import io.doindev.cvector.cli.scan.ScanRequest;
import io.doindev.cvector.cli.scan.ScanResult;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfig.ProjectEntry;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Drives the MCP {@code cv_scan_project} tool by handing the request to
 * {@link InProcessScanService}. The scan runs inside the dashboard JVM, reusing the live
 * {@link GraphStore} when its underlying database matches the target — which means embedded
 * Kuzu workspaces can finally re-scan from an MCP call without crashing on Kuzu's per-process
 * directory lock (the failure mode the old subprocess implementation hit).
 *
 * <p>For shared-DB workspaces every project resolves to the same Kuzu directory, so the live
 * store is always reused. For isolated-DB workspaces the store is reused only when scanning
 * the active project; scanning a non-active isolated project opens a fresh Kuzu handle on
 * that project's own directory (different lock, no conflict).
 *
 * <p>Output is teed to a per-scan log file under {@code ~/.cvector/logs/} so the operator
 * always has the full transcript even when the JSON response only carries a tail. The file
 * path goes back in the response under {@code logFile}.
 */
@Component
@Profile("mcp")
public class CvectorScanService {

    private final InProcessScanService scanner;
    private final GraphStore store;
    private final CvectorConfigService configService;

    public CvectorScanService(InProcessScanService scanner, GraphStore store, CvectorConfigService configService) {
        this.scanner = scanner;
        this.store = store;
        this.configService = configService;
    }

    public Map<String, Object> scan(ProjectEntry target) {
        if (target.rootPath() == null) {
            throw new IllegalArgumentException(
                    "project '" + target.name() + "' has no rootPath — cv_scan_project needs a directory to walk");
        }
        Path rootPath = Paths.get(target.rootPath());
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException(
                    "project '" + target.name() + "' rootPath does not exist or isn't a directory: " + rootPath);
        }

        CvectorConfig cfg = loadConfig();
        ProjectContext ctx = new ProjectContext(target.projectId(), target.name(), rootPath);
        Path logFile = openScanLogFile(target.projectId());
        Consumer<String> tee = teeTo(logFile);

        long start = System.currentTimeMillis();
        ScanResult result = null;
        Throwable failure = null;
        try {
            ScanRequest request = ScanRequest.builder()
                    .config(cfg)
                    .context(ctx)
                    .scanRoot(rootPath)
                    .progress(tee)
                    .reusedStore(store)
                    .build();
            result = scanner.scan(request);
        } catch (Throwable t) {
            failure = t;
            tee.accept("scan failed: " + t);
            for (StackTraceElement el : t.getStackTrace()) {
                tee.accept("  at " + el);
            }
            Throwable cause = t.getCause();
            while (cause != null && cause != t) {
                tee.accept("Caused by: " + cause);
                cause = cause.getCause();
            }
        }
        long elapsedMs = System.currentTimeMillis() - start;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failure == null);
        out.put("elapsedMs", elapsedMs);
        out.put("projectId", target.projectId());
        out.put("project", target.name());
        out.put("rootPath", target.rootPath());
        out.put("workingDir", rootPath.toString());
        if (logFile != null) out.put("logFile", logFile.toString());

        if (result != null) {
            out.put("backend", result.backend());
            if (result.dbPath() != null) out.put("dbPath", result.dbPath());
            if (result.mode() != null) out.put("mode", result.mode());
            out.put("filesScanned", result.filesScanned());
            out.put("filesSkipped", result.filesSkipped());
            out.put("nodesUpserted", result.nodesUpserted());
            out.put("edgesUpserted", result.edgesUpserted());
            out.put("handlersLinked", result.handlersLinked());
            out.put("callsResolved", result.callsResolved());
            out.put("staleRemoved", result.staleRemoved());
            out.put("summary", String.format("scanned %d file(s) in %dms",
                    result.filesScanned(), result.parserMs() + result.flushMs()));
            out.put("ingestSummary", String.format("nodes upserted: %d, edges upserted: %d",
                    result.nodesUpserted(), result.edgesUpserted()));
            if (result.callsResolved() > 0) {
                out.put("resolveSummary", String.format("resolved %d cross-file call(s)", result.callsResolved()));
            }
            if (result.staleRemoved() > 0) {
                out.put("cleanupSummary", String.format("removed %d stale node(s) from prior scans", result.staleRemoved()));
            }
            List<String> log = result.log();
            int from = Math.max(0, log.size() - 30);
            out.put("logTail", log.subList(from, log.size()));
        }
        if (failure != null) {
            out.put("exceptionClass", failure.getClass().getName());
            String msg = failure.getMessage();
            if (msg != null) out.put("exceptionMessage", msg);
            List<String> causes = new ArrayList<>();
            Throwable c = failure.getCause();
            while (c != null && c != failure) {
                causes.add(c.getClass().getName() + (c.getMessage() == null ? "" : ": " + c.getMessage()));
                c = c.getCause();
            }
            if (!causes.isEmpty()) out.put("causes", causes);
        }
        return out;
    }

    private CvectorConfig loadConfig() {
        // Re-read settings.json on every scan so a project that was just added via
        // cv_add_project (and lives only on disk, not in the bean snapshot) is picked up.
        // The config service is the shared singleton bean, so this stays cheap.
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException(
                    "No .cvector/settings.json found from " + cwd + " (also checked your user home directory).");
        }
        try {
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path openScanLogFile(String projectId) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".cvector", "logs");
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            Path file = dir.resolve("scan-" + projectId + "-" + stamp + ".log");
            Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private static Consumer<String> teeTo(Path logFile) {
        if (logFile == null) return s -> { /* no-op when log file creation failed */ };
        return line -> {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND))) {
                w.println(line);
            } catch (IOException ignored) { /* best-effort */ }
        };
    }
}
