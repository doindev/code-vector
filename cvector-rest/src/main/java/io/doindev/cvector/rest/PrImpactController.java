package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Should this PR scare me?" view: for every file changed against the base branch, surface
 * the symbols defined in that file and their downstream blast radius.
 *
 * <p>{@code GET /api/pr-impact?base=main&depth=3}. Cached through {@link GraphReadCache}
 * keyed on (base, depth); the base SHA is also embedded so a force-push or branch rebase
 * invalidates the cache implicitly (the SHA changes → key changes).
 *
 * <p>We shell out to {@code git} on the active project's root. If git is missing or the base
 * ref doesn't resolve, the endpoint returns an explanatory error rather than a 500.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class PrImpactController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public PrImpactController(GraphStore restGraphStore, ActiveProject activeProject,
                              JsonCache jsonCache, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
        this.mapper = mapper;
    }

    /**
     * Returns pre-serialised JSON bytes on cache hit. The response is dominated by per-file
     * rows (one entry per changed File node), so for a 200-file PR the payload is ~30-50 KB —
     * worth caching as bytes so the Jackson cost lands once per (base, depth) pair.
     */
    @GetMapping(value = "/pr-impact", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] prImpact(
            @RequestParam(value = "base", defaultValue = "main") String base,
            @RequestParam(value = "depth", defaultValue = "3") int depth) {
        int effectiveDepth = clamp(depth, 1, 8);
        Path repo = projectRoot();
        Map<String, Object> error = checkRepo(repo);
        if (error != null) return serialise(error);
        String baseSha = resolveSha(repo, base);
        if (baseSha == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "cannot resolve base ref '" + base + "'");
            out.put("base", base);
            return serialise(out);
        }
        String key = "pr-impact:" + project.projectId() + ":" + baseSha + ":d=" + effectiveDepth;
        return jsonCache.memoize(key, () -> compute(repo, base, baseSha, effectiveDepth));
    }

    private byte[] serialise(Object o) {
        try {
            return mapper.writeValueAsBytes(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ("{\"error\":\"serialisation-failed\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> compute(Path repo, String base, String baseSha, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", project.projectId(), "name", project.name()));
        out.put("base", base);
        out.put("baseSha", baseSha);
        out.put("depth", depth);

        List<String> changed = diffNames(repo, baseSha);
        out.put("changedFileCount", changed.size());
        if (changed.isEmpty()) {
            out.put("files", List.of());
            return out;
        }

        // 4 round trips total instead of 2N+2M:
        //   1) bulkFilesByPath — every changed path → fileId in one shot.
        //   2) bulkContains    — every file's symbols in one shot.
        //   3) bulkCallerCounts — one grouped count for every symbol id at once.
        //   4) bulkImpactedIds  — one variable-length traversal for every symbol id.
        // For a 200-file PR with ~5 symbols per file the old loop did ~2000 round trips
        // on cold; this collapses to 4 plus the cache hit on warm.
        Map<String, String> pathToFileId = store.bulkFilesByPath(project.projectId(), changed);
        List<String> fileIds = new ArrayList<>(pathToFileId.values());
        Map<String, List<Map<String, Object>>> childrenByFile =
                store.bulkContains(project.projectId(), fileIds, 200);

        // Flatten every symbol id we need to interrogate.
        List<String> allSymbolIds = new ArrayList<>();
        for (List<Map<String, Object>> bucket : childrenByFile.values()) {
            for (Map<String, Object> sym : bucket) {
                Object idObj = sym.get("id");
                if (idObj != null) allSymbolIds.add(idObj.toString());
            }
        }
        Map<String, Long> callerCounts = store.bulkCallerCounts(project.projectId(), allSymbolIds);
        Map<String, Set<String>> impactedBySymbol = store.bulkImpactedIds(project.projectId(), allSymbolIds, depth);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String rel : changed) {
            String fileId = pathToFileId.get(rel);
            if (fileId == null) continue;
            List<Map<String, Object>> symbols = childrenByFile.getOrDefault(fileId, List.of());

            Set<String> downstreamIds = new LinkedHashSet<>();
            long callerCount = 0;
            for (Map<String, Object> sym : symbols) {
                Object idObj = sym.get("id");
                if (idObj == null) continue;
                String id = idObj.toString();
                callerCount += callerCounts.getOrDefault(id, 0L);
                Set<String> impacted = impactedBySymbol.get(id);
                if (impacted != null) downstreamIds.addAll(impacted);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", rel);
            row.put("symbolsTouched", symbols.size());
            row.put("incomingCallers", callerCount);
            row.put("downstreamReach", downstreamIds.size());
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare(
                ((Number) b.getOrDefault("downstreamReach", 0)).intValue(),
                ((Number) a.getOrDefault("downstreamReach", 0)).intValue()));
        out.put("files", rows);
        return out;
    }

    private Path projectRoot() {
        String root = project.rootPath();
        if (root == null || root.isBlank()) return Paths.get("").toAbsolutePath();
        return Paths.get(root);
    }

    private static Map<String, Object> checkRepo(Path repo) {
        if (runGit(repo, "rev-parse", "--is-inside-work-tree") == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "not a git repo: " + repo);
            return out;
        }
        return null;
    }

    private static String resolveSha(Path repo, String ref) {
        return runGit(repo, "rev-parse", "--verify", ref + "^{commit}");
    }

    private static List<String> diffNames(Path repo, String baseSha) {
        List<String> out = new ArrayList<>();
        String diff = runGit(repo, "diff", "--name-only", baseSha, "HEAD");
        if (diff != null) {
            for (String line : diff.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        String status = runGit(repo, "status", "--porcelain");
        if (status != null) {
            for (String line : status.split("\n")) {
                String t = line.length() > 3 ? line.substring(3).trim() : "";
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    private static String runGit(Path root, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(root.toString());
        for (String a : args) cmd.add(a);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) return null;
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
