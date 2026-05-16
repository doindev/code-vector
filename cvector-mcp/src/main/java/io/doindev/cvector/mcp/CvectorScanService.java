package io.doindev.cvector.mcp;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfig.ProjectEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wraps {@code cvector scan} for invocation from the MCP {@code cv_scan_project} tool.
 *
 * <p>Today the implementation is a subprocess shell-out: the MCP server JVM is already
 * holding a {@link io.doindev.cvector.core.store.GraphStore GraphStore} handle (and on
 * embedded Kuzu, the file lock that goes with it), so re-running the scan in-process
 * against the same store would either deadlock on Kuzu's lock or interleave writes with
 * concurrent reads from other tool calls. Spawning a child {@code cvector.exe scan} with
 * an environment that re-points the active project gives us isolation and reuses every
 * existing scan optimisation (parallel parser walk, content-hash skip, bulk vs. merge
 * mode, post-scan reconciliation). The trade-off is ~3-5 s of JVM-warmup per scan; for
 * an LLM agent on a fresh codebase that's well under the parse cost itself.
 *
 * <p>A future revision can extract the scan loop from {@code ScanCommand} into a shared
 * service that runs in-process when the GraphStore is one of the shared-DB variants and
 * the file lock isn't a problem (Neo4j) — at which point this class becomes a thin
 * router. For now: shell out, parse output, return the summary.
 */
public final class CvectorScanService {

    private CvectorScanService() {}

    public static Map<String, Object> scan(ProjectResolver projects, ProjectEntry target) {
        Path rootPath = Paths.get(target.rootPath());
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException(
                    "project '" + target.name() + "' rootPath does not exist or isn't a directory: " + rootPath);
        }
        Path exe = findCvectorExecutable();
        if (exe == null) {
            throw new IllegalStateException(
                    "cv_scan_project: couldn't locate the cvector executable on PATH or in $LOCALAPPDATA\\Programs\\cvector. "
                            + "Either add cvector to PATH or run `cvector scan` from the CLI manually for project '"
                            + target.name() + "'.");
        }

        // Pre-flight: ensure the target project is the active one for the duration of the
        // scan. We persist the change so the spawned cvector.exe (which loads settings.json
        // fresh) picks the right project, then restore the previous activeProject on
        // success or failure so the parent's worldview isn't disturbed.
        CvectorConfig before = projects.loadConfig();
        String savedActive = before.activeProject();
        String desiredActive = null;
        for (Map.Entry<String, ProjectEntry> en : before.projects().entrySet()) {
            if (target.projectId().equals(en.getValue().projectId())) {
                desiredActive = en.getKey();
                break;
            }
        }
        if (desiredActive == null) {
            throw new IllegalStateException("project " + target.projectId() + " not in settings.json after resolve — config raced?");
        }
        boolean restore = !desiredActive.equals(savedActive);
        if (restore) {
            CvectorConfig swap = new CvectorConfig(
                    desiredActive, before.projects(), before.neo4j(), before.backend(),
                    before.rest(), before.mcp(), before.docker(), before.rules(), before.kuzu());
            try { projects.service().save(projects.configRoot(), swap); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }

        long start = System.currentTimeMillis();
        List<String> stdoutLines = new ArrayList<>();
        int exitCode;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "scan");
            pb.directory(rootPath.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdoutLines.add(line);
                }
            }
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new RuntimeException("cv_scan_project timed out after 10 minutes for project " + target.name());
            }
            exitCode = p.exitValue();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("cv_scan_project subprocess failed: " + e.getMessage(), e);
        } finally {
            if (restore) {
                CvectorConfig latest = projects.loadConfig();
                CvectorConfig restored = new CvectorConfig(
                        savedActive, latest.projects(), latest.neo4j(), latest.backend(),
                        latest.rest(), latest.mcp(), latest.docker(), latest.rules(), latest.kuzu());
                try { projects.service().save(projects.configRoot(), restored); }
                catch (IOException ignored) { /* best-effort restore */ }
            }
        }
        long elapsedMs = System.currentTimeMillis() - start;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", exitCode == 0);
        out.put("exitCode", exitCode);
        out.put("elapsedMs", elapsedMs);
        out.put("projectId", target.projectId());
        out.put("project", target.name());
        out.put("rootPath", target.rootPath());
        // Parse the canonical scan-summary line ("scanned N file(s) in Xms" / "nodes upserted: ...").
        for (String line : stdoutLines) {
            if (line.startsWith("scanned ")) out.put("summary", line.trim());
            if (line.startsWith("nodes upserted: ")) out.put("ingestSummary", line.trim());
            if (line.startsWith("resolved ")) out.put("resolveSummary", line.trim());
            if (line.startsWith("removed ")) out.put("cleanupSummary", line.trim());
        }
        // Tail of the output for diagnostic context (last 20 lines).
        int from = Math.max(0, stdoutLines.size() - 20);
        out.put("logTail", stdoutLines.subList(from, stdoutLines.size()));
        return out;
    }

    /**
     * Best-effort discovery of the cvector binary. Order:
     * <ol>
     *   <li>{@code CVECTOR_EXE} environment variable (explicit override).</li>
     *   <li>{@code $LOCALAPPDATA\Programs\cvector\cvector.exe} on Windows (the install
     *       script's default destination).</li>
     *   <li>{@code cvector} on PATH (best-effort — relies on the OS shell expansion).</li>
     * </ol>
     */
    private static Path findCvectorExecutable() {
        String envOverride = System.getenv("CVECTOR_EXE");
        if (envOverride != null && !envOverride.isBlank()) {
            Path p = Paths.get(envOverride);
            if (Files.isExecutable(p)) return p;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path windows = Paths.get(localAppData, "Programs", "cvector", "cvector.exe");
            if (Files.isExecutable(windows)) return windows;
        }
        // Linux / macOS likely path
        Path linuxLocal = Paths.get(System.getProperty("user.home"), ".local", "bin", "cvector");
        if (Files.isExecutable(linuxLocal)) return linuxLocal;
        // Fallback: assume on PATH
        String which = System.getProperty("os.name", "").toLowerCase().contains("win") ? "cvector.exe" : "cvector";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "where" : "which",
                    which);
            Process p = pb.start();
            String first;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                first = r.readLine();
            }
            p.waitFor(2, TimeUnit.SECONDS);
            if (first != null && !first.isBlank()) {
                Path p2 = Paths.get(first.trim());
                if (Files.isExecutable(p2)) return p2;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
