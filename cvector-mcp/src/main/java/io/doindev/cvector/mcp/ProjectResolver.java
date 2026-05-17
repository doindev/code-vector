package io.doindev.cvector.mcp;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves project name-or-UUID-or-path strings supplied by MCP clients to canonical
 * {@link CvectorConfig.ProjectEntry} records. Centralised here so every tool method has a
 * single, consistent error message for "project not found" — listing the valid names is
 * the difference between an LLM correcting itself in one turn vs. flailing.
 *
 * <p>Lookup precedence in {@link #resolve}:
 * <ol>
 *   <li>Exact match against {@code projects.*.name} (also the map key).</li>
 *   <li>Exact match against {@code projects.*.projectId}.</li>
 *   <li>Absolute path normalisation: if the input is a directory path and it matches an
 *       existing project's {@code rootPath} (either exactly or as a descendant), that
 *       project wins.</li>
 * </ol>
 *
 * <p>{@code "*"} is reserved as the cross-project sentinel; {@link #isWildcard} surfaces
 * it for tools that opt into multi-project queries.
 *
 * <p>Re-reads {@code settings.json} on every call (no caching) so {@code cv_add_project}
 * writes are visible to subsequent tool calls in the same {@code cvector serve} session.
 */
public final class ProjectResolver {

    public static final String WILDCARD = "*";

    private final CvectorConfigService configService;
    private final GraphStore store;

    public ProjectResolver(CvectorConfigService configService, GraphStore store) {
        this.configService = configService;
        this.store = store;
    }

    public boolean isWildcard(String input) {
        return WILDCARD.equals(input);
    }

    /** Snapshot of the current workspace config. Re-read from disk every call. */
    public CvectorConfig loadConfig() {
        try {
            Path root = configRoot();
            return configService.load(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the resolved {@code .cvector/} or home-fallback directory containing settings.json. */
    public Path configRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = configService.findConfigRoot(cwd);
        if (root == null) {
            throw new IllegalStateException(
                    "No .cvector/settings.json found. Run `cvector init`, or create one at ~/.cvector/settings.json.");
        }
        return root;
    }

    /**
     * Look up a project by name, UUID, or directory path. Throws with a descriptive
     * error (listing valid names + ids) when nothing matches — the message is designed
     * to give the LLM enough context to self-correct on the next tool call.
     */
    public CvectorConfig.ProjectEntry resolve(String nameOrIdOrPath) {
        if (nameOrIdOrPath == null || nameOrIdOrPath.isBlank()) {
            throw new IllegalArgumentException("project parameter is required");
        }
        if (WILDCARD.equals(nameOrIdOrPath)) {
            throw new IllegalArgumentException(
                    "project '*' is the wildcard for cross-project queries — this tool requires a specific projectId");
        }
        CvectorConfig cfg = loadConfig();
        CvectorConfig.ProjectEntry hit = findInternal(cfg, nameOrIdOrPath);
        if (hit != null) return hit;
        throw new IllegalArgumentException(formatUnknownProjectError(nameOrIdOrPath, cfg));
    }

    /**
     * Non-throwing variant for the {@code cv_find_project} tool — returns {@code null}
     * when nothing matches so the tool can return a structured "found: false" payload
     * with candidate suggestions instead of an exception.
     */
    public CvectorConfig.ProjectEntry find(String nameOrIdOrPath) {
        if (nameOrIdOrPath == null || nameOrIdOrPath.isBlank()) return null;
        return findInternal(loadConfig(), nameOrIdOrPath);
    }

    /**
     * Resolve a project parameter that may be omitted: when {@code nameOrIdOrPath} is
     * {@code null} or blank, fall back to the workspace's {@code activeProject} (treated
     * as the user-level default). Throws with a clear "no default available" message
     * when both the input and the active-project pointer are missing — that's a
     * configuration-error state worth flagging rather than silently picking a random
     * project.
     *
     * <p>Used by every MCP tool so the agent (and CLI users via the matching flag) can
     * elide the {@code project} argument for queries against the workspace's default
     * project, while still allowing explicit overrides to target other projects.
     */
    public CvectorConfig.ProjectEntry resolveOrDefault(String nameOrIdOrPath) {
        if (nameOrIdOrPath == null || nameOrIdOrPath.isBlank()) {
            CvectorConfig cfg = loadConfig();
            String active = cfg.activeProject();
            if (active == null || active.isBlank()) {
                throw new IllegalStateException(
                        "no `project` argument supplied and the workspace has no activeProject set in settings.json. "
                                + "Either pass a project (name, UUID, or rootPath) or run `cvector project switch <name>` to pick a default.");
            }
            CvectorConfig.ProjectEntry def = cfg.projects().get(active);
            if (def == null) {
                throw new IllegalStateException(
                        "settings.json activeProject='" + active + "' but no matching entry in projects map. "
                                + "Edit settings.json or run `cvector project switch <name>`.");
            }
            return def;
        }
        return resolve(nameOrIdOrPath);
    }

    /** Whether the workspace currently has a default (active) project set. */
    public boolean hasDefault() {
        CvectorConfig cfg = loadConfig();
        return cfg.activeProject() != null
                && !cfg.activeProject().isBlank()
                && cfg.projects().containsKey(cfg.activeProject());
    }

    private CvectorConfig.ProjectEntry findInternal(CvectorConfig cfg, String input) {
        // Pass 1: by name (also the map key — most common case).
        CvectorConfig.ProjectEntry byName = cfg.projects().get(input);
        if (byName != null) return byName;
        // Pass 2: by UUID.
        for (CvectorConfig.ProjectEntry e : cfg.projects().values()) {
            if (input.equals(e.projectId())) return e;
        }
        // Pass 3: by path. ONLY attempt path matching when the input is unambiguously an
        // absolute filesystem path. Earlier revisions passed any string through
        // {@link Paths#get(String)}{@code .toAbsolutePath()}, which silently joined a bare
        // identifier with the JVM's current working directory — so an agent calling
        // {@code cv_scan_project({project: "does-not-exist-xyzzy"})} from a dashboard whose
        // CWD happened to be a registered project's root would get a "successful" scan of
        // that project instead of a clear "no such project" error. Tightening to absolute-
        // path-only matches the documented contract ("absolute path normalisation") and
        // eliminates the footgun.
        if (looksLikeAbsolutePath(input)) {
            Path inputPath = tryAbsoluteNormalize(input);
            if (inputPath != null) {
                CvectorConfig.ProjectEntry exact = null;
                CvectorConfig.ProjectEntry ancestor = null;
                int bestAncestorDepth = Integer.MAX_VALUE;
                for (CvectorConfig.ProjectEntry e : cfg.projects().values()) {
                    if (e.rootPath() == null) continue;
                    Path candidate = tryAbsoluteNormalize(e.rootPath());
                    if (candidate == null) continue;
                    if (candidate.equals(inputPath)) {
                        exact = e;
                        break;
                    }
                    if (inputPath.startsWith(candidate)) {
                        // Pick the deepest matching ancestor so /work/mono/services/auth wins
                        // over /work/mono when both happen to be registered projects.
                        int depth = inputPath.getNameCount() - candidate.getNameCount();
                        if (depth >= 0 && depth < bestAncestorDepth) {
                            bestAncestorDepth = depth;
                            ancestor = e;
                        }
                    }
                }
                if (exact != null) return exact;
                if (ancestor != null) return ancestor;
            }
        }
        return null;
    }

    /**
     * True when the input is unambiguously an absolute filesystem path (POSIX, Windows
     * drive-letter, or UNC). Bare names and UUIDs return false so they don't get joined
     * with the JVM's CWD and accidentally match a registered project root.
     */
    private static boolean looksLikeAbsolutePath(String input) {
        if (input == null || input.isEmpty()) return false;
        char c0 = input.charAt(0);
        if (c0 == '/' || c0 == '\\') return true;
        if (input.length() >= 2 && Character.isLetter(c0) && input.charAt(1) == ':') return true;
        return false;
    }

    /**
     * Validate that a proposed new project rootPath doesn't overlap with any existing
     * project's rootPath. Rejects two overlap shapes:
     *
     * <ul>
     *   <li><b>Inside an existing project:</b> the new path is the same as, or a descendant
     *       of, an existing project's rootPath. Onboarding the inner directory would create
     *       two projects whose graphs both contain the same source files — the cleanup
     *       semantics (which project owns this file?) get ambiguous fast.</li>
     *   <li><b>Containing an existing project:</b> the new path is an ancestor of an
     *       existing project's rootPath. Onboarding the outer directory would re-scan the
     *       inner project's files under a different projectId, duplicating data and
     *       breaking the inner project's incremental-scan caches.</li>
     * </ul>
     *
     * <p>Throws {@link IllegalArgumentException} with the conflicting project name in the
     * message so the agent (or CLI user) can see exactly which entry blocks the add.
     *
     * @param normalizedNewRoot the canonicalised target rootPath (already passed through
     *                          {@link Path#toAbsolutePath} + {@link Path#normalize})
     */
    public void validateAddable(Path normalizedNewRoot) {
        if (normalizedNewRoot == null) {
            throw new IllegalArgumentException("rootPath is required");
        }
        Path candidate = tryAbsoluteNormalize(normalizedNewRoot.toString());
        if (candidate == null) {
            throw new IllegalArgumentException("rootPath could not be resolved: " + normalizedNewRoot);
        }
        for (CvectorConfig.ProjectEntry e : loadConfig().projects().values()) {
            if (e.rootPath() == null) continue;
            Path existing = tryAbsoluteNormalize(e.rootPath());
            if (existing == null) continue;
            if (candidate.equals(existing)) {
                throw new IllegalArgumentException(
                        "rootPath '" + normalizedNewRoot + "' is already registered as project '"
                                + e.name() + "' (" + e.projectId() + "). Use cv_find_project to look it up.");
            }
            if (candidate.startsWith(existing)) {
                throw new IllegalArgumentException(
                        "rootPath '" + normalizedNewRoot + "' is inside an existing project '"
                                + e.name() + "' (rooted at " + e.rootPath()
                                + "). Onboarding a sub-directory of an existing project would create overlapping graphs. "
                                + "Either query the parent project directly, or remove the parent project first.");
            }
            if (existing.startsWith(candidate)) {
                throw new IllegalArgumentException(
                        "rootPath '" + normalizedNewRoot + "' would contain an existing project '"
                                + e.name() + "' (rooted at " + e.rootPath()
                                + "). Onboarding a parent of an existing project would re-scan the inner project's files "
                                + "under a different projectId. Either remove the inner project first, or pick a non-overlapping rootPath.");
            }
        }
    }

    private static Path tryAbsoluteNormalize(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            Path p = Paths.get(s).toAbsolutePath().normalize();
            // Real path resolution (which collapses symlinks) only works when the path
            // exists. Fall through to the normalised non-real path if it doesn't.
            try { return p.toRealPath(); }
            catch (IOException ignored) { return p; }
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public CvectorConfigService service() {
        return configService;
    }

    public GraphStore store() {
        return store;
    }

    private static String formatUnknownProjectError(String input, CvectorConfig cfg) {
        StringBuilder sb = new StringBuilder("unknown project '").append(input).append("'. ");
        if (cfg.projects().isEmpty()) {
            sb.append("No projects defined yet. Use cv_add_project(name, rootPath) to register one.");
        } else {
            sb.append("Valid projects: ");
            List<String> names = new ArrayList<>(cfg.projects().keySet());
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i);
                String pid = cfg.projects().get(n).projectId();
                if (i > 0) sb.append(", ");
                sb.append(n).append(" (").append(pid).append(")");
            }
            sb.append(". Tools accept project name, UUID, or rootPath. Call cv_list_projects for full metadata.");
        }
        return sb.toString();
    }

    /**
     * Rich project metadata for the {@code cv_list_projects} tool. Includes everything an
     * agent needs to disambiguate projects without follow-up tool calls: name, UUID, root
     * path, isolation flag, active flag, last-scan metadata, and live node/edge counts.
     *
     * <p>Counts come from the live {@link GraphStore} so they reflect the current data
     * (not stale from settings.json). If the store can't reach the project for any reason
     * (Neo4j down, isolated DB doesn't exist yet, etc.) the count fields are null rather
     * than 0 so the agent can tell "empty" from "unknown".
     */
    public List<Map<String, Object>> listProjects() {
        CvectorConfig cfg = loadConfig();
        String backendName = store != null ? store.backend() : "unknown";
        List<Map<String, Object>> out = new ArrayList<>(cfg.projects().size());
        for (Map.Entry<String, CvectorConfig.ProjectEntry> en : cfg.projects().entrySet()) {
            CvectorConfig.ProjectEntry p = en.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", en.getKey());
            row.put("projectId", p.projectId());
            row.put("rootPath", p.rootPath());
            row.put("isolated", p.isolatedOrDefault());
            row.put("sharedDb", cfg.isSharedDbMode(p.projectId()));
            row.put("active", Objects.equals(en.getKey(), cfg.activeProject()));
            row.put("backend", backendName);

            // Best-effort live counts + scan metadata. Failures are swallowed — the
            // structural metadata above is always populated regardless.
            try {
                Map<String, Long> nodes = store.nodeCounts(p.projectId());
                Map<String, Long> edges = store.edgeCounts(p.projectId());
                long totalNodes = nodes.values().stream().mapToLong(Long::longValue).sum();
                long totalEdges = edges.values().stream().mapToLong(Long::longValue).sum();
                row.put("totalNodes", totalNodes);
                row.put("totalEdges", totalEdges);
                row.put("scanned", totalNodes > 0);
                Map<String, Object> meta = store.projectMeta(p.projectId());
                if (meta != null && !meta.isEmpty()) {
                    if (meta.containsKey("lastScanCommit")) row.put("lastScanCommit", meta.get("lastScanCommit"));
                    if (meta.containsKey("lastScanAt")) row.put("lastScanAt", meta.get("lastScanAt"));
                }
            } catch (RuntimeException ignored) {
                // Backend unreachable for this project — leave counts unset.
                row.put("totalNodes", null);
                row.put("totalEdges", null);
                row.put("scanned", null);
            }
            out.add(row);
        }
        return out;
    }
}
