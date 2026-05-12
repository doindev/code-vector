package io.doindev.cvector.core;

import java.util.Set;

public enum CvectorRole {

    DEV(Set.of("*"), Set.of("*")),

    ARCHITECT(
            Set.of(
                    "cv_stats", "cv_projects", "cv_search", "cv_context", "cv_explain", "cv_impact",
                    "cv_test_impact", "cv_communities", "cv_flows", "cv_rules", "cv_guard", "cv_diff", "cv_service_links"
            ),
            Set.of(
                    "/api/health", "/api/stats", "/api/projects",
                    "/api/search", "/api/explain", "/api/impact", "/api/test-impact"
            )
    ),

    SECURITY(
            Set.of("cv_audit", "cv_guard", "cv_rules", "cv_health", "cv_stats"),
            Set.of("/api/health", "/api/stats", "/api/audit", "/api/guard", "/api/rules")
    ),

    PM(
            Set.of("cv_stats", "cv_projects", "cv_onboard", "cv_changes", "cv_health", "cv_wiki"),
            Set.of("/api/health", "/api/stats", "/api/projects", "/api/onboard")
    );

    private final Set<String> tools;
    private final Set<String> restPaths;

    CvectorRole(Set<String> tools, Set<String> restPaths) {
        this.tools = tools;
        this.restPaths = restPaths;
    }

    public boolean allowsTool(String toolName) {
        return tools.contains("*") || tools.contains(toolName);
    }

    public boolean allowsRestPath(String path) {
        if (restPaths.contains("*")) return true;
        for (String allowed : restPaths) {
            if (path.equals(allowed) || path.startsWith(allowed + "/")) return true;
        }
        return false;
    }

    public Set<String> tools() { return tools; }
    public Set<String> restPaths() { return restPaths; }

    public static CvectorRole resolve() {
        String env = System.getenv("cvector_role");
        if (env == null || env.isBlank()) env = System.getenv("CVECTOR_ROLE");
        if (env == null || env.isBlank()) return DEV;
        try {
            return valueOf(env.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEV;
        }
    }
}
