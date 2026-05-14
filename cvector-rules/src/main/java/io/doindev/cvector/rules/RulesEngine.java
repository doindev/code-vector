package io.doindev.cvector.rules;

import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.builtin.CustomCypherRule;
import io.doindev.cvector.rules.builtin.DeadCodeRule;
import io.doindev.cvector.rules.builtin.DeepInheritanceRule;
import io.doindev.cvector.rules.builtin.GodClassRule;
import io.doindev.cvector.rules.builtin.GodFileRule;
import io.doindev.cvector.rules.builtin.LongMethodRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RulesEngine {

    public static List<Rule> defaultRules() {
        return List.of(
                new GodFileRule(),
                new GodClassRule(),
                new LongMethodRule(),
                new DeepInheritanceRule(),
                new DeadCodeRule()
        );
    }

    public record RuleRun(String rule, Severity severity, int violations, List<Violation> findings) {}

    public record Report(String projectId, List<RuleRun> runs) {
        public int totalViolations() { return runs.stream().mapToInt(RuleRun::violations).sum(); }
        public boolean hasErrors() {
            return runs.stream().anyMatch(r -> r.severity == Severity.ERROR && r.violations > 0);
        }
        public Map<String, Integer> bySeverity() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Severity s : Severity.values()) m.put(s.name(), 0);
            for (RuleRun r : runs) {
                m.merge(r.severity.name(), r.violations, Integer::sum);
            }
            return m;
        }
    }

    private final String projectId;
    private final GraphStore store;
    private final RulesConfig config;

    public RulesEngine(String projectId, GraphStore store, RulesConfig config) {
        this.projectId = projectId;
        this.store = store;
        this.config = config;
    }

    public Report run() {
        List<RuleRun> runs = new ArrayList<>();
        for (Rule r : defaultRules()) {
            if (config.isDisabled(r.name())) continue;
            List<Violation> v = r.evaluate(projectId, store, config);
            runs.add(new RuleRun(r.name(), r.severity(), v.size(), v));
        }
        if (config.getCustom() != null) {
            for (RulesConfig.CustomRule def : config.getCustom()) {
                if (def.getName() == null || config.isDisabled(def.getName())) continue;
                CustomCypherRule cr = new CustomCypherRule(def);
                List<Violation> v = cr.evaluate(projectId, store, config);
                runs.add(new RuleRun(cr.name(), cr.severity(), v.size(), v));
            }
        }
        return new Report(projectId, runs);
    }
}
