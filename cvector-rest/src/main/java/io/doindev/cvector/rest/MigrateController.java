package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structured migration plan for the dashboard's Migrate view. Mirrors the
 * {@code cvector migrate} CLI: composes findSymbol + callers + references + importers +
 * testReach into a sequenced plan with a risk score so a user can size the work before
 * starting it.
 *
 * <p>{@code GET /api/migrate?symbol=&to=&depth=}. JsonCache-wrapped; key includes every
 * dimension that changes the result.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class MigrateController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public MigrateController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/migrate", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] migrate(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "depth", defaultValue = "5") int depth) {
        int safeDepth = Math.max(1, Math.min(depth, 12));
        String key = "migrate:" + project.projectId() + ":" + symbol + ":to=" + (to == null ? "" : to)
                + ":d=" + safeDepth;
        return jsonCache.memoize(key, () -> build(symbol, to, safeDepth));
    }

    private Map<String, Object> build(String symbol, String to, int depth) {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", pid, "name", project.name()));
        out.put("query", symbol);
        out.put("to", to);
        out.put("depth", depth);

        List<Map<String, Object>> hits = store.findSymbol(pid, symbol);
        if (hits.isEmpty()) {
            out.put("found", false);
            return out;
        }
        Map<String, Object> hit = hits.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        if (hit.get("fileId") != null) {
            out.put("file", store.fileOf(pid, (String) hit.get("fileId")));
        }

        List<Map<String, Object>> callers = store.callers(pid, id);
        List<Map<String, Object>> references = store.referencingNodes(pid, id, 500);
        List<Map<String, Object>> importers = store.importingFiles(pid, id, 200);
        List<Map<String, Object>> tests = store.testReach(pid, id, depth);

        // Bucket callers by coarse module prefix (first 3 dotted segments). Same heuristic
        // as the CLI MigrateCommand — keeping the shape identical lets a future consolidation
        // share a serialiser.
        Map<String, Integer> byModule = new TreeMap<>();
        for (Map<String, Object> c : callers) {
            String fqn = String.valueOf(c.get("fqName"));
            byModule.merge(modulePrefix(fqn), 1, Integer::sum);
        }

        int risk = riskScore(callers.size(), tests.size(), references.size());
        out.put("risk", risk);
        out.put("riskLabel", riskLabel(risk));
        out.put("counts", Map.of(
                "callers", callers.size(),
                "references", references.size(),
                "importers", importers.size(),
                "tests", tests.size(),
                "modules", byModule.size()
        ));
        out.put("byModule", byModule);
        out.put("callers", callers);
        out.put("references", references);
        out.put("importers", importers);
        out.put("tests", tests);
        out.put("sequencing", buildSequencing(to, tests.size()));
        return out;
    }

    private static List<String> buildSequencing(String to, int testCount) {
        List<String> steps = new java.util.ArrayList<>(4);
        steps.add("Extract a thin wrapper or feature flag around the target symbol.");
        if (testCount > 0) {
            steps.add("Verify the " + testCount + " covering test(s) still pass against the wrapper.");
        } else {
            steps.add("Add tests covering at least one caller before swapping the impl.");
        }
        if (to != null && !to.isBlank()) {
            steps.add("Migrate callers module-by-module to " + to + ", largest module last.");
        } else {
            steps.add("Refactor callers module-by-module to the new shape, largest module last.");
        }
        steps.add("Once all callers migrated, delete the wrapper and the original symbol.");
        return steps;
    }

    private static int riskScore(int callers, int tests, int references) {
        int callPart = Math.min(60, callers * 6);
        int refPart = Math.min(20, references * 2 / 5);
        int testPart = tests > 0 ? 0 : 20;
        return Math.min(100, callPart + refPart + testPart);
    }

    private static String riskLabel(int score) {
        if (score >= 75) return "HIGH";
        if (score >= 40) return "MEDIUM";
        return "LOW";
    }

    private static String modulePrefix(String fqName) {
        if (fqName == null) return "(unknown)";
        String[] parts = fqName.split("\\.");
        if (parts.length <= 3) return fqName;
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}
