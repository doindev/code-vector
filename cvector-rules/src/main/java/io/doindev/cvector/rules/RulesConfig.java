package io.doindev.cvector.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RulesConfig {

    public static final RulesConfig DEFAULTS = defaults();

    private Map<String, Object> thresholds = new LinkedHashMap<>();
    private List<CustomRule> custom = new ArrayList<>();
    private List<String> disable = new ArrayList<>();
    private List<String> excludePaths = new ArrayList<>();

    public Map<String, Object> getThresholds() { return thresholds; }
    public void setThresholds(Map<String, Object> thresholds) { this.thresholds = thresholds == null ? new LinkedHashMap<>() : thresholds; }
    public List<CustomRule> getCustom() { return custom; }
    public void setCustom(List<CustomRule> custom) { this.custom = custom == null ? new ArrayList<>() : custom; }
    public List<String> getDisable() { return disable; }
    public void setDisable(List<String> disable) { this.disable = disable == null ? new ArrayList<>() : disable; }
    public List<String> getExcludePaths() { return excludePaths; }
    public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths == null ? new ArrayList<>() : excludePaths; }

    /** Returns true if the path matches any excludePaths pattern (substring match; `**` allowed). */
    public boolean isPathExcluded(String path) {
        if (path == null || excludePaths.isEmpty()) return false;
        String normalized = path.replace('\\', '/');
        for (String pattern : excludePaths) {
            if (pattern == null || pattern.isBlank()) continue;
            String p = pattern.replace('\\', '/');
            if (p.contains("**")) {
                String[] parts = p.split("\\*\\*");
                int idx = 0;
                boolean matched = true;
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    int found = normalized.indexOf(part, idx);
                    if (found < 0) { matched = false; break; }
                    idx = found + part.length();
                }
                if (matched) return true;
            } else if (normalized.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public int intThreshold(String key, int fallback) {
        Object v = thresholds.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
        return fallback;
    }

    public boolean isDisabled(String ruleName) {
        return disable.contains(ruleName);
    }

    public static RulesConfig defaults() {
        RulesConfig c = new RulesConfig();
        c.thresholds.put("godFileMethods", 30);
        c.thresholds.put("godClassMethods", 20);
        c.thresholds.put("longMethodLines", 80);
        c.thresholds.put("deepInheritance", 5);
        c.excludePaths.add("/internal/");
        c.excludePaths.add("/target/");
        c.excludePaths.add("/generated-sources/");
        return c;
    }

    public static class CustomRule {
        private String name;
        private String description;
        private Severity severity = Severity.WARN;
        private String cypher;
        private String cypherKuzu;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Severity getSeverity() { return severity; }
        public void setSeverity(Severity severity) { this.severity = severity; }
        public String getCypher() { return cypher; }
        public void setCypher(String cypher) { this.cypher = cypher; }
        public String getCypherKuzu() { return cypherKuzu; }
        public void setCypherKuzu(String cypherKuzu) { this.cypherKuzu = cypherKuzu; }

        /**
         * Per-backend Cypher selection. Prefers {@code cypherKuzu} on the embedded backend, falls
         * back to {@code cypher} (which is what every existing rules.yml carries).
         */
        public String getCypherFor(String backend) {
            if ("kuzu".equals(backend) && cypherKuzu != null && !cypherKuzu.isBlank()) {
                return cypherKuzu;
            }
            return cypher;
        }
    }
}
