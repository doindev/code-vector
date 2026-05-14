package io.doindev.cvector.rules;

import io.doindev.cvector.core.config.CvectorConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Merges the four sources of rules configuration into a single {@link RulesConfig} that the
 * rules engine can consume.
 *
 * <p>Layer order (lowest precedence first; later layers win on conflicts):
 * <ol>
 *   <li>{@link RulesConfig#defaults()} — hard-coded thresholds + the two universal excludePaths.</li>
 *   <li>{@code .cvector/rules.yml} — legacy file-based config. Still supported so existing
 *       projects keep working unchanged.</li>
 *   <li>{@link CvectorConfig#rules()} — workspace-wide policy applied to every project in
 *       the workspace.</li>
 *   <li>{@link CvectorConfig.ProjectEntry#rules()} — per-project override that only applies
 *       when commands target that project.</li>
 * </ol>
 *
 * <p>Per-field merge semantics:
 * <ul>
 *   <li>{@code thresholds} — map merge by key; a later layer's value replaces an earlier
 *       layer's value for the same key, but keys not mentioned by the later layer keep
 *       whatever the earlier layer set.</li>
 *   <li>{@code disable} — union across all layers; once a rule is disabled at any layer it
 *       stays disabled.</li>
 *   <li>{@code excludePaths} — union with deduplication; ordering reflects layer order
 *       (defaults first, per-project last).</li>
 *   <li>{@code custom} — by-name override. If a later layer defines a custom rule with the
 *       same {@code name} as an earlier layer, the later layer replaces it wholesale
 *       (cypher, severity, description).</li>
 * </ul>
 */
public final class RulesConfigResolver {

    private RulesConfigResolver() {}

    /**
     * Resolve the effective rules config for {@code projectKey} (the key under
     * {@link CvectorConfig#projects()}, typically the project name). When {@code config} is
     * {@code null} only defaults + rules.yml are considered, which matches the behaviour from
     * before the workspace policy existed.
     */
    public static RulesConfig resolve(CvectorConfig config, String projectKey, Path rulesYml) {
        RulesConfig base = RulesConfig.defaults();
        if (rulesYml != null) {
            // RulesConfigLoader returns defaults on a missing file, so we'd overwrite ourselves
            // with the same data. Only layer when the file is actually there.
            if (java.nio.file.Files.exists(rulesYml)) {
                RulesConfig fromYml = RulesConfigLoader.loadOrDefault(rulesYml);
                applyRulesConfig(base, fromYml);
            }
        }
        if (config != null) {
            applyPolicy(base, config.rules());
            if (projectKey != null && config.projects() != null) {
                CvectorConfig.ProjectEntry pe = config.projects().get(projectKey);
                if (pe != null) applyPolicy(base, pe.rules());
            }
        }
        return base;
    }

    /** Apply a {@link RulesConfig} (loaded from rules.yml) as an overlay onto {@code base}. */
    private static void applyRulesConfig(RulesConfig base, RulesConfig overlay) {
        if (overlay == null) return;
        if (overlay.getThresholds() != null) {
            for (Map.Entry<String, Object> e : overlay.getThresholds().entrySet()) {
                base.getThresholds().put(e.getKey(), e.getValue());
            }
        }
        if (overlay.getDisable() != null) {
            for (String d : overlay.getDisable()) addUnique(base.getDisable(), d);
        }
        if (overlay.getExcludePaths() != null) {
            for (String x : overlay.getExcludePaths()) addUnique(base.getExcludePaths(), x);
        }
        if (overlay.getCustom() != null) {
            for (RulesConfig.CustomRule cr : overlay.getCustom()) {
                if (cr == null || cr.getName() == null) continue;
                base.getCustom().removeIf(existing -> cr.getName().equals(existing.getName()));
                base.getCustom().add(cr);
            }
        }
    }

    /** Apply a {@link CvectorConfig.RulesPolicy} (from settings.json) as an overlay. */
    private static void applyPolicy(RulesConfig base, CvectorConfig.RulesPolicy policy) {
        if (policy == null) return;
        if (policy.thresholds() != null) {
            for (Map.Entry<String, Integer> e : policy.thresholds().entrySet()) {
                if (e.getKey() == null) continue;
                base.getThresholds().put(e.getKey(), e.getValue());
            }
        }
        if (policy.disable() != null) {
            for (String d : policy.disable()) addUnique(base.getDisable(), d);
        }
        if (policy.excludePaths() != null) {
            for (String x : policy.excludePaths()) addUnique(base.getExcludePaths(), x);
        }
        if (policy.custom() != null) {
            for (CvectorConfig.CustomPolicy cp : policy.custom()) {
                if (cp == null || cp.name() == null || cp.name().isBlank()) continue;
                base.getCustom().removeIf(existing -> cp.name().equals(existing.getName()));
                base.getCustom().add(toCustomRule(cp));
            }
        }
    }

    private static RulesConfig.CustomRule toCustomRule(CvectorConfig.CustomPolicy cp) {
        RulesConfig.CustomRule cr = new RulesConfig.CustomRule();
        cr.setName(cp.name());
        cr.setDescription(cp.description());
        if (cp.severity() != null && !cp.severity().isBlank()) {
            // Severity is an enum (ERROR / WARN / INFO). Fall back to WARN — the existing
            // default for custom rules — on any parse miss so a typo doesn't bring the
            // rules engine down at boot.
            try {
                cr.setSeverity(Severity.valueOf(cp.severity().trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                cr.setSeverity(Severity.WARN);
            }
        }
        cr.setCypher(cp.cypher());
        cr.setCypherKuzu(cp.cypherKuzu());
        return cr;
    }

    private static <T> void addUnique(List<T> list, T value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        if (!list.contains(value)) list.add(value);
    }

    /** Used by callers that have a project key but no rules.yml path. */
    public static RulesConfig resolve(CvectorConfig config, String projectKey) {
        return resolve(config, projectKey, null);
    }

    /** Used by callers that have just the file path (no settings.json access). Compat shim. */
    public static RulesConfig resolveFromFileOnly(Path rulesYml) {
        return resolve(null, null, rulesYml);
    }
}
