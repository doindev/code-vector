package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuBulkLoader;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Component
@Command(name = "diff",
        description = "Compare graph drift between two git commits: cvector diff <sha1> <sha2>.",
        mixinStandardHelpOptions = true)
public class DiffCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Base commit (older).")
    private String shaA;

    @Parameters(index = "1", description = "Target commit (newer).")
    private String shaB;

    @Option(names = "--repo", description = "Path to the git repo (default: active project rootPath).")
    private Path repoPath;

    @Option(names = "--keep", description = "Keep snapshot data after the diff (Neo4j: as pidA/pidB project nodes; Kuzu: as temp directories). Default: cleanup.")
    private boolean keep;

    @Option(names = "--include-calls", description = "Also diff CALLS edges (heavier query).")
    private boolean includeCalls;

    private final CvectorRuntime runtime;
    private final List<Parser> parsers;

    public DiffCommand(CvectorRuntime runtime, List<Parser> parsers) {
        this.runtime = runtime;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        Path repo = repoPath != null
                ? repoPath.toAbsolutePath().normalize()
                : Paths.get(active.rootPath()).toAbsolutePath().normalize();

        if (!GitHelper.isGitRepo(repo)) {
            System.err.println("not a git repo: " + repo);
            return 2;
        }
        Optional<String> resolvedA = GitHelper.resolveSha(repo, shaA);
        Optional<String> resolvedB = GitHelper.resolveSha(repo, shaB);
        if (resolvedA.isEmpty()) { System.err.println("cannot resolve sha: " + shaA); return 2; }
        if (resolvedB.isEmpty()) { System.err.println("cannot resolve sha: " + shaB); return 2; }
        String fullA = resolvedA.get();
        String fullB = resolvedB.get();

        if (fullA.equals(fullB)) {
            System.out.println("both SHAs resolve to the same commit; no diff to compute");
            return 0;
        }

        return CvectorRuntime.isEmbeddedRequested()
                ? runEmbedded(cfg, active, repo, fullA, fullB)
                : runNeo4j(cfg, active, repo, fullA, fullB);
    }

    private Integer runNeo4j(CvectorConfig cfg, CvectorConfig.ProjectEntry active,
                             Path repo, String fullA, String fullB) throws Exception {
        String pidA = active.projectId() + "__sha_" + fullA.substring(0, 12);
        String pidB = active.projectId() + "__sha_" + fullB.substring(0, 12);

        Path worktreeA = createWorktreePath(fullA);
        Path worktreeB = createWorktreePath(fullB);

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            new SchemaBootstrap(client).bootstrap();

            System.out.println("=== diff " + shortSha(fullA) + " ↔ " + shortSha(fullB) + " (backend: neo4j) ===");
            System.out.println("repo:    " + repo);
            System.out.println("base:    " + fullA);
            System.out.println("target:  " + fullB);

            checkoutAndScan(repo, fullA, worktreeA, pidA, active.name() + "@" + shortSha(fullA), client);
            checkoutAndScan(repo, fullB, worktreeB, pidB, active.name() + "@" + shortSha(fullB), client);

            renderDiff(new GraphQueries(client), pidA, pidB);

            if (!keep) {
                cleanupSnapshots(client, pidA, pidB);
                System.out.println();
                System.out.println("snapshots removed from Neo4j (use --keep to retain).");
            } else {
                System.out.println();
                System.out.println("snapshots retained: " + pidA + ", " + pidB);
            }
        } finally {
            GitHelper.worktreeRemove(repo, worktreeA);
            GitHelper.worktreeRemove(repo, worktreeB);
            tryDeleteDir(worktreeA);
            tryDeleteDir(worktreeB);
        }
        return 0;
    }

    /**
     * Embedded diff: two temp Kuzu DBs, one per SHA, each bulk-loaded via {@link KuzuBulkLoader}.
     * The cross-snapshot diff that runs as Neo4j Cypher with {@code NOT EXISTS} doesn't translate
     * cleanly to Kuzu's one-DB-per-project model, so we compute added/removed/changed sets in
     * Java from the rows each DB returns.
     */
    private Integer runEmbedded(CvectorConfig cfg, CvectorConfig.ProjectEntry active,
                                Path repo, String fullA, String fullB) throws Exception {
        Path tmpRoot = Files.createTempDirectory("cvector-diff-");
        Path dbA = tmpRoot.resolve("a.kuzu");
        Path dbB = tmpRoot.resolve("b.kuzu");
        Path worktreeA = createWorktreePath(fullA);
        Path worktreeB = createWorktreePath(fullB);
        String pidA = active.projectId() + "__sha_" + fullA.substring(0, 12);
        String pidB = active.projectId() + "__sha_" + fullB.substring(0, 12);

        try {
            System.out.println("=== diff " + shortSha(fullA) + " ↔ " + shortSha(fullB) + " (backend: kuzu) ===");
            System.out.println("repo:    " + repo);
            System.out.println("base:    " + fullA);
            System.out.println("target:  " + fullB);

            // Phase 1: scan snapshot A. Open-close so the COPY-FROM bulk path is exercised against
            // a freshly-bootstrapped empty DB.
            try (EmbeddedKuzu kuzu = new EmbeddedKuzu(dbA, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                checkoutAndScanKuzu(repo, fullA, worktreeA, pidA,
                        active.name() + "@" + shortSha(fullA), kuzu);
            }
            // Phase 2: scan snapshot B into its own DB.
            try (EmbeddedKuzu kuzu = new EmbeddedKuzu(dbB, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                new KuzuSchemaBootstrap(kuzu).bootstrap();
                checkoutAndScanKuzu(repo, fullB, worktreeB, pidB,
                        active.name() + "@" + shortSha(fullB), kuzu);
            }
            // Phase 3: reopen both side-by-side and diff in Java.
            try (EmbeddedKuzu kuzuA = new EmbeddedKuzu(dbA, EmbeddedKuzu.bufferSizeFromConfig(cfg));
                 EmbeddedKuzu kuzuB = new EmbeddedKuzu(dbB, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                renderDiffKuzu(kuzuA, kuzuB);
            }

            if (keep) {
                System.out.println();
                System.out.println("snapshots retained at:");
                System.out.println("  " + dbA);
                System.out.println("  " + dbB);
            }
        } finally {
            GitHelper.worktreeRemove(repo, worktreeA);
            GitHelper.worktreeRemove(repo, worktreeB);
            tryDeleteDir(worktreeA);
            tryDeleteDir(worktreeB);
            if (!keep) tryDeleteDir(tmpRoot);
        }
        return 0;
    }

    private void checkoutAndScan(Path repo, String sha, Path worktree, String pid, String displayName,
                                 Neo4jClient client) throws IOException {
        System.out.println();
        System.out.println("scan @ " + shortSha(sha) + " → " + pid);
        if (Files.exists(worktree)) {
            GitHelper.worktreeRemove(repo, worktree);
            tryDeleteDir(worktree);
        }
        if (!GitHelper.worktreeAdd(repo, worktree, sha)) {
            throw new IllegalStateException("`git worktree add` failed for " + sha + " at " + worktree);
        }
        ProjectContext ctx = new ProjectContext(pid, displayName, worktree);
        for (Parser p : parsers) p.prepare(ctx);
        int files = 0;
        try (Ingestor ingestor = new Ingestor(client)) {
            try (Stream<Path> walk = Files.walk(worktree)) {
                var iter = walk.filter(Files::isRegularFile).filter(DiffCommand::notIgnored).iterator();
                while (iter.hasNext()) {
                    Path file = iter.next();
                    for (Parser p : parsers) {
                        if (!p.accepts(file)) continue;
                        try { p.parse(file, ctx, ingestor); files++; }
                        catch (RuntimeException ignored) {}
                        break;
                    }
                }
            }
            for (Parser p : parsers) p.finish();
            ingestor.flush();
            System.out.printf("  scanned %d file(s); nodes=%d edges=%d%n",
                    files, ingestor.totalNodes(), ingestor.totalEdges());
        }
    }

    private void renderDiff(GraphQueries q, String pidA, String pidB) {
        section("Files");
        renderAddRemove(q, "File", pidA, pidB, "path");
        section("Classes");
        renderAddRemove(q, "Class", pidA, pidB, "fqName");
        section("Methods");
        renderAddRemove(q, "Method", pidA, pidB, "fqName");
        section("API endpoints");
        renderAddRemove(q, "ApiEndpoint", pidA, pidB, "fqName");
        section("Maven dependencies");
        renderDependencyDiff(q, pidA, pidB);
        section("Tables");
        renderAddRemove(q, "Table", pidA, pidB, "name");

        if (includeCalls) {
            section("CALLS edges (added)");
            print(q.raw(
                    "MATCH (a:Method {projectId: $b})-[:CALLS]->(b:Method) WHERE b.projectId = $b "
                            + "AND NOT EXISTS { MATCH (aa:Method {projectId: $a, fqName: a.fqName})-[:CALLS]->(bb:Method {projectId: $a, fqName: b.fqName}) } "
                            + "RETURN a.fqName AS caller, b.fqName AS callee LIMIT 200",
                    Map.of("a", pidA, "b", pidB)));
            section("CALLS edges (removed)");
            print(q.raw(
                    "MATCH (a:Method {projectId: $a})-[:CALLS]->(b:Method) WHERE b.projectId = $a "
                            + "AND NOT EXISTS { MATCH (aa:Method {projectId: $b, fqName: a.fqName})-[:CALLS]->(bb:Method {projectId: $b, fqName: b.fqName}) } "
                            + "RETURN a.fqName AS caller, b.fqName AS callee LIMIT 200",
                    Map.of("a", pidA, "b", pidB)));
        }
    }

    private static void renderAddRemove(GraphQueries q, String label, String pidA, String pidB, String key) {
        List<Map<String, Object>> added = q.raw(
                "MATCH (b:" + label + " {projectId: $b}) "
                        + "WHERE NOT EXISTS { MATCH (a:" + label + " {projectId: $a, " + key + ": b." + key + "}) } "
                        + "RETURN b." + key + " AS " + key + " ORDER BY b." + key + " LIMIT 500",
                Map.of("a", pidA, "b", pidB));
        List<Map<String, Object>> removed = q.raw(
                "MATCH (a:" + label + " {projectId: $a}) "
                        + "WHERE NOT EXISTS { MATCH (b:" + label + " {projectId: $b, " + key + ": a." + key + "}) } "
                        + "RETURN a." + key + " AS " + key + " ORDER BY a." + key + " LIMIT 500",
                Map.of("a", pidA, "b", pidB));
        System.out.println("  added: " + added.size() + "  removed: " + removed.size());
        if (!added.isEmpty()) {
            System.out.println("  + added:");
            int shown = 0;
            for (Map<String, Object> r : added) {
                if (shown++ >= 25) { System.out.println("    ... (" + (added.size() - shown + 1) + " more)"); break; }
                System.out.println("    + " + r.get(key));
            }
        }
        if (!removed.isEmpty()) {
            System.out.println("  - removed:");
            int shown = 0;
            for (Map<String, Object> r : removed) {
                if (shown++ >= 25) { System.out.println("    ... (" + (removed.size() - shown + 1) + " more)"); break; }
                System.out.println("    - " + r.get(key));
            }
        }
    }

    private static void renderDependencyDiff(GraphQueries q, String pidA, String pidB) {
        List<Map<String, Object>> rows = q.raw(
                "OPTIONAL MATCH (a:MavenDependency {projectId: $a}) "
                        + "OPTIONAL MATCH (b:MavenDependency {projectId: $b}) "
                        + "WITH collect(DISTINCT {coord: a.fqName, version: a.version}) AS as, "
                        + "     collect(DISTINCT {coord: b.fqName, version: b.version}) AS bs "
                        + "RETURN as, bs",
                Map.of("a", pidA, "b", pidB));
        if (rows.isEmpty()) { System.out.println("  (no dependency data)"); return; }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aList = (List<Map<String, Object>>) rows.get(0).get("as");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bList = (List<Map<String, Object>>) rows.get(0).get("bs");
        var aMap = new java.util.LinkedHashMap<String, String>();
        var bMap = new java.util.LinkedHashMap<String, String>();
        if (aList != null) for (var m : aList) {
            Object c = m.get("coord");
            if (c != null) aMap.put(String.valueOf(c), String.valueOf(m.get("version")));
        }
        if (bList != null) for (var m : bList) {
            Object c = m.get("coord");
            if (c != null) bMap.put(String.valueOf(c), String.valueOf(m.get("version")));
        }
        var added = new java.util.ArrayList<String>();
        var removed = new java.util.ArrayList<String>();
        var changed = new java.util.ArrayList<String>();
        for (var entry : bMap.entrySet()) {
            if (!aMap.containsKey(entry.getKey())) added.add(entry.getKey() + ":" + entry.getValue());
            else if (!String.valueOf(aMap.get(entry.getKey())).equals(entry.getValue())) {
                changed.add(entry.getKey() + " (" + aMap.get(entry.getKey()) + " → " + entry.getValue() + ")");
            }
        }
        for (var entry : aMap.entrySet()) {
            if (!bMap.containsKey(entry.getKey())) removed.add(entry.getKey() + ":" + entry.getValue());
        }
        added.sort(Comparator.naturalOrder());
        removed.sort(Comparator.naturalOrder());
        changed.sort(Comparator.naturalOrder());
        System.out.printf("  added: %d  removed: %d  version-changed: %d%n", added.size(), removed.size(), changed.size());
        for (String s : added) System.out.println("    + " + s);
        for (String s : removed) System.out.println("    - " + s);
        for (String s : changed) System.out.println("    ~ " + s);
    }

    private static void cleanupSnapshots(Neo4jClient client, String pidA, String pidB) {
        client.write("MATCH (n) WHERE n.projectId IN [$a, $b] DETACH DELETE n",
                Map.of("a", pidA, "b", pidB));
    }

    // ===== Embedded (Kuzu) diff helpers =====

    private void checkoutAndScanKuzu(Path repo, String sha, Path worktree, String pid,
                                      String displayName, EmbeddedKuzu kuzu) throws IOException {
        System.out.println();
        System.out.println("scan @ " + shortSha(sha) + " → " + pid);
        if (Files.exists(worktree)) {
            GitHelper.worktreeRemove(repo, worktree);
            tryDeleteDir(worktree);
        }
        if (!GitHelper.worktreeAdd(repo, worktree, sha)) {
            throw new IllegalStateException("`git worktree add` failed for " + sha + " at " + worktree);
        }
        ProjectContext ctx = new ProjectContext(pid, displayName, worktree);
        for (Parser p : parsers) p.prepare(ctx);
        int[] files = {0};
        try (KuzuBulkLoader ingestor = new KuzuBulkLoader(kuzu)) {
            try (Stream<Path> walk = Files.walk(worktree)) {
                walk.filter(Files::isRegularFile).filter(DiffCommand::notIgnored).forEach(file -> {
                    for (Parser p : parsers) {
                        if (!p.accepts(file)) continue;
                        try { p.parse(file, ctx, ingestor); files[0]++; }
                        catch (RuntimeException ignored) { /* per-file parse failure */ }
                        break;
                    }
                });
            }
            for (Parser p : parsers) p.finish();
            ingestor.flush();
            System.out.printf("  scanned %d file(s); nodes=%d edges=%d%n",
                    files[0], ingestor.totalNodes(), ingestor.totalEdges());
        }
    }

    private void renderDiffKuzu(EmbeddedKuzu a, EmbeddedKuzu b) {
        section("Files");
        diffByLabelKuzu(a, b, "File", "path");
        section("Classes");
        diffByLabelKuzu(a, b, "Class", "fqName");
        section("Methods");
        diffByLabelKuzu(a, b, "Method", "fqName");
        section("API endpoints");
        diffByLabelKuzu(a, b, "ApiEndpoint", "fqName");
        section("Maven dependencies");
        diffDependenciesKuzu(a, b);
        section("Tables");
        diffByLabelKuzu(a, b, "Table", "name");

        if (includeCalls) {
            section("CALLS edges");
            diffCallsKuzu(a, b);
        }
    }

    private static void diffByLabelKuzu(EmbeddedKuzu a, EmbeddedKuzu b, String label, String keyProp) {
        TreeSet<String> keysA = readKeysKuzu(a, label, keyProp);
        TreeSet<String> keysB = readKeysKuzu(b, label, keyProp);
        TreeSet<String> added = new TreeSet<>(keysB);
        added.removeAll(keysA);
        TreeSet<String> removed = new TreeSet<>(keysA);
        removed.removeAll(keysB);
        System.out.println("  added: " + added.size() + "  removed: " + removed.size());
        if (!added.isEmpty()) {
            System.out.println("  + added:");
            renderTruncated(added, '+', 25);
        }
        if (!removed.isEmpty()) {
            System.out.println("  - removed:");
            renderTruncated(removed, '-', 25);
        }
    }

    private static TreeSet<String> readKeysKuzu(EmbeddedKuzu kuzu, String label, String keyProp) {
        // Each snapshot DB holds exactly one projectId so we can skip the projectId filter.
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (n:Node) WHERE n.label = $label RETURN n." + keyProp + " AS k",
                Map.of("label", label));
        TreeSet<String> out = new TreeSet<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get("k");
            if (v != null) out.add(v.toString());
        }
        return out;
    }

    private static void renderTruncated(TreeSet<String> values, char marker, int limit) {
        int shown = 0;
        for (String v : values) {
            if (shown++ >= limit) {
                System.out.println("    ... (" + (values.size() - shown + 1) + " more)");
                break;
            }
            System.out.println("    " + marker + " " + v);
        }
    }

    private static void diffDependenciesKuzu(EmbeddedKuzu a, EmbeddedKuzu b) {
        Map<String, String> aMap = readDependenciesKuzu(a);
        Map<String, String> bMap = readDependenciesKuzu(b);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : bMap.entrySet()) {
            String coord = entry.getKey();
            String bVersion = entry.getValue();
            if (!aMap.containsKey(coord)) {
                added.add(coord + ":" + bVersion);
            } else if (!String.valueOf(aMap.get(coord)).equals(bVersion)) {
                changed.add(coord + " (" + aMap.get(coord) + " → " + bVersion + ")");
            }
        }
        for (Map.Entry<String, String> entry : aMap.entrySet()) {
            if (!bMap.containsKey(entry.getKey())) removed.add(entry.getKey() + ":" + entry.getValue());
        }
        added.sort(Comparator.naturalOrder());
        removed.sort(Comparator.naturalOrder());
        changed.sort(Comparator.naturalOrder());
        System.out.printf("  added: %d  removed: %d  version-changed: %d%n",
                added.size(), removed.size(), changed.size());
        for (String s : added) System.out.println("    + " + s);
        for (String s : removed) System.out.println("    - " + s);
        for (String s : changed) System.out.println("    ~ " + s);
    }

    private static Map<String, String> readDependenciesKuzu(EmbeddedKuzu kuzu) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (d:Node) WHERE d.label = 'MavenDependency' "
                        + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, d.version AS version");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object g = r.get("groupId");
            Object aid = r.get("artifactId");
            if (g == null || aid == null) continue;
            out.put(g + ":" + aid, String.valueOf(r.get("version")));
        }
        return out;
    }

    private static void diffCallsKuzu(EmbeddedKuzu a, EmbeddedKuzu b) {
        TreeSet<String> pairsA = readCallPairsKuzu(a);
        TreeSet<String> pairsB = readCallPairsKuzu(b);
        TreeSet<String> added = new TreeSet<>(pairsB);
        added.removeAll(pairsA);
        TreeSet<String> removed = new TreeSet<>(pairsA);
        removed.removeAll(pairsB);
        System.out.println("  added edges:   " + added.size());
        System.out.println("  removed edges: " + removed.size());
        int shown = 0;
        for (String p : added) {
            if (shown++ >= 50) { System.out.println("    ... (" + (added.size() - shown + 1) + " more added)"); break; }
            System.out.println("    + " + p);
        }
        shown = 0;
        for (String p : removed) {
            if (shown++ >= 50) { System.out.println("    ... (" + (removed.size() - shown + 1) + " more removed)"); break; }
            System.out.println("    - " + p);
        }
    }

    private static TreeSet<String> readCallPairsKuzu(EmbeddedKuzu kuzu) {
        List<Map<String, Object>> rows = kuzu.read(
                "MATCH (caller:Node)-[:CALLS]->(callee:Node) "
                        + "WHERE caller.label = 'Method' AND callee.label = 'Method' "
                        + "RETURN caller.fqName AS src, callee.fqName AS dst");
        TreeSet<String> out = new TreeSet<>();
        for (Map<String, Object> r : rows) {
            out.add(r.get("src") + " -> " + r.get("dst"));
        }
        return out;
    }

    private static Path createWorktreePath(String sha) {
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "cvector-diff-" + shortSha(sha) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String shortSha(String sha) {
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private static boolean notIgnored(Path p) {
        for (Path part : p) {
            String name = part.getFileName().toString();
            if (name.equals("target") || name.equals("build") || name.equals("node_modules")
                    || name.equals(".git") || name.equals(".cvector")) {
                return false;
            }
        }
        return true;
    }

    private static void tryDeleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void print(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) { System.out.println("  (none)"); return; }
        TableRenderer.render(System.out, rows);
    }
}
