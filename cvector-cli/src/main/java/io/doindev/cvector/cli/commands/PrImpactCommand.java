package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code cvector pr-impact} — for every file changed against the base branch (default
 * {@code main}), enumerate the symbols defined in those files and the downstream blast
 * radius (callers, references, downstream calls). Useful as a "should this PR scare me?"
 * snapshot during review.
 *
 * <p>Uses the indexed graph state, not the diff itself, so a method that is referenced
 * indirectly via a callback still shows up. The flip side: a file edited but not yet
 * re-scanned won't reflect its new edges — callers should ensure {@code cvector scan} or
 * {@code scan:incremental} ran before this.
 */
@Component
@Command(name = "pr-impact",
        description = "Show downstream impact of files changed against the base branch (default 'main').",
        mixinStandardHelpOptions = true)
public class PrImpactCommand implements Callable<Integer> {

    @Option(names = "--base", description = "Base branch to diff against (default: main).")
    private String base = "main";

    @Option(names = "--depth", description = "Max BFS depth from each symbol (default 3).")
    private int depth = 3;

    @Option(names = "--json", description = "Emit JSON instead of a human-readable table.")
    private boolean json;

    private final CvectorRuntime runtime;

    public PrImpactCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        Path repo = active.rootPath() == null
                ? Paths.get("").toAbsolutePath()
                : Paths.get(active.rootPath());

        if (!GitHelper.isGitRepo(repo)) {
            System.err.println("not a git repo: " + repo);
            return 2;
        }
        String baseSha = GitHelper.resolveSha(repo, base).orElse(null);
        if (baseSha == null) {
            System.err.println("could not resolve base ref: " + base);
            return 2;
        }
        List<Path> changed = GitHelper.changedSince(repo, baseSha);
        if (changed.isEmpty()) {
            System.out.println("no files changed against " + base + " (" + baseSha.substring(0, 7) + ")");
            return 0;
        }

        try (GraphStore store = runtime.openGraphStore(cfg)) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("base", base);
            report.put("baseSha", baseSha);
            report.put("changedFileCount", changed.size());
            List<Map<String, Object>> rows = new ArrayList<>();

            for (Path file : changed) {
                String relative = repo.relativize(file).toString().replace('\\', '/');
                // Find the File node for this path (cvector stores paths relative to project root).
                List<Map<String, Object>> fileHits = store.searchByName(active.projectId(), relative, "File", 1);
                if (fileHits.isEmpty()) continue;
                String fileId = String.valueOf(fileHits.get(0).get("id"));

                // For each defined symbol in this file, sum the downstream impact. We cap depth and
                // dedup by id so a method that's both a caller and a callee doesn't double-count.
                List<Map<String, Object>> symbols = store.contains(active.projectId(), fileId, 200);
                Set<String> downstreamIds = new LinkedHashSet<>();
                int callerCount = 0;
                for (Map<String, Object> sym : symbols) {
                    Object idObj = sym.get("id");
                    if (idObj == null) continue;
                    String id = idObj.toString();
                    callerCount += store.callers(active.projectId(), id).size();
                    for (Map<String, Object> imp : store.impactDownstream(active.projectId(), id, depth)) {
                        Object impId = imp.get("id");
                        if (impId != null) downstreamIds.add(impId.toString());
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", relative);
                row.put("symbolsTouched", symbols.size());
                row.put("incomingCallers", callerCount);
                row.put("downstreamReach", downstreamIds.size());
                rows.add(row);
            }
            // Order by blast-radius descending so the riskiest files surface first.
            rows.sort((a, b) -> Integer.compare(
                    ((Number) b.getOrDefault("downstreamReach", 0)).intValue(),
                    ((Number) a.getOrDefault("downstreamReach", 0)).intValue()));
            report.put("files", rows);

            if (json) {
                System.out.println(toJson(report));
                return 0;
            }
            renderHuman(report);
            return 0;
        }
    }

    private static void renderHuman(Map<String, Object> report) {
        System.out.println("pr-impact: base=" + report.get("base")
                + " (" + String.valueOf(report.get("baseSha")).substring(0, 7) + ")");
        System.out.println("changed files: " + report.get("changedFileCount"));
        System.out.println();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("files");
        if (rows.isEmpty()) {
            System.out.println("(no changed files matched ingested File nodes — run `cvector scan` first?)");
            return;
        }
        System.out.printf("%-70s %8s %8s %10s%n", "file", "symbols", "callers", "downstream");
        System.out.printf("%-70s %8s %8s %10s%n",
                "-".repeat(70), "-------", "-------", "----------");
        for (Map<String, Object> r : rows) {
            String f = String.valueOf(r.get("file"));
            if (f.length() > 68) f = "…" + f.substring(f.length() - 67);
            System.out.printf("%-70s %8s %8s %10s%n",
                    f, r.get("symbolsTouched"), r.get("incomingCallers"), r.get("downstreamReach"));
        }
    }

    /** Minimal JSON without bringing in Jackson — the report shape is flat and stable. */
    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"base\":\"").append(esc(String.valueOf(report.get("base")))).append("\",");
        sb.append("\"baseSha\":\"").append(esc(String.valueOf(report.get("baseSha")))).append("\",");
        sb.append("\"changedFileCount\":").append(report.get("changedFileCount")).append(",");
        sb.append("\"files\":[");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("files");
        boolean first = true;
        for (Map<String, Object> r : rows) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
                    .append("\"file\":\"").append(esc(String.valueOf(r.get("file")))).append("\",")
                    .append("\"symbolsTouched\":").append(r.get("symbolsTouched")).append(",")
                    .append("\"incomingCallers\":").append(r.get("incomingCallers")).append(",")
                    .append("\"downstreamReach\":").append(r.get("downstreamReach"))
                    .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
