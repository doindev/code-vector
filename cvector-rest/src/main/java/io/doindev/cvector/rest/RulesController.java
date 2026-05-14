package io.doindev.cvector.rest;

import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigLoader;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Violation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Architecture-rule evaluation surface: runs {@link RulesEngine} against the active project's
 * graph and returns a structured per-rule report. Loads {@code .cvector/rules.yml} from the
 * project root if present, otherwise falls back to {@link RulesConfig#defaults()} so projects
 * that haven't run {@code cvector rules --init} still see meaningful output.
 *
 * <p>Cached through {@link GraphReadCache} -- the underlying rule fan-out hits the store many
 * times (god files, dead code, long methods, deep inheritance) and the answer is stable until
 * the next scan lands. Cache invalidation happens via {@link GraphMutatedEvent} so a fresh
 * scan or watcher tick refreshes the next read.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class RulesController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;
    private final CvectorConfigService configService;

    public RulesController(GraphStore restGraphStore,
                           ActiveProject activeProject,
                           JsonCache jsonCache,
                           CvectorConfigService configService) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
        this.configService = configService;
    }

    @GetMapping(value = "/rules", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] rules() {
        return jsonCache.memoize("rules:" + project.projectId(), this::buildReport);
    }

    private Map<String, Object> buildReport() {
        Path configRoot = resolveConfigRoot();
        Path rulesPath = configRoot != null
                ? configRoot.resolve(".cvector").resolve("rules.yml")
                : null;
        RulesConfig rulesCfg = RulesConfigLoader.loadOrDefault(rulesPath);

        RulesEngine engine = new RulesEngine(project.projectId(), store, rulesCfg);
        RulesEngine.Report report = engine.run();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", project.projectId(),
                "name", project.name()
        ));
        out.put("rulesPath", rulesPath == null ? null : rulesPath.toString());
        out.put("rulesYmlExists", rulesPath != null && java.nio.file.Files.exists(rulesPath));
        out.put("totals", report.bySeverity());
        out.put("totalViolations", report.totalViolations());
        out.put("hasErrors", report.hasErrors());

        List<Map<String, Object>> runs = new ArrayList<>(report.runs().size());
        for (RulesEngine.RuleRun run : report.runs()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rule", run.rule());
            r.put("severity", run.severity().name());
            r.put("violations", run.violations());
            r.put("findings", serialiseFindings(run.findings()));
            runs.add(r);
        }
        out.put("runs", runs);
        return out;
    }

    private static List<Map<String, Object>> serialiseFindings(List<Violation> findings) {
        // Cap per-rule findings on the wire so a "1000 long methods" run doesn't push a
        // multi-megabyte payload to the dashboard. The Rules view shows the first 100 per
        // rule; the rest stay visible via the totals count.
        int cap = 100;
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(cap, findings.size()));
        int shown = 0;
        for (Violation v : findings) {
            if (shown++ >= cap) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", v.subject());
            m.put("message", v.message());
            m.put("severity", v.severity().name());
            m.put("fileId", v.fileId());
            m.put("line", v.line());
            rows.add(m);
        }
        return rows;
    }

    private Path resolveConfigRoot() {
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            return configService.findConfigRoot(cwd);
        } catch (Exception e) {
            return null;
        }
    }
}
