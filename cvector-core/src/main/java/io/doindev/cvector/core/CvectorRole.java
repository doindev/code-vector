package io.doindev.cvector.core;

import java.util.Set;

public enum CvectorRole {

    DEV(Set.of("*"), Set.of("*")),

    // Tool names below MUST match the @Tool(name=...) values in CvectorTools. The previous
    // ARCHITECT / PM allow-lists referenced cv_projects and cv_diff — names that were retired
    // in the 0.2.0 multi-project / async-job refactor. Effect: those roles silently denied
    // every legitimate replacement tool until this enum was updated. cv_projects is now split
    // into cv_list_projects + cv_find_project; cv_diff is now cv_diff_start + cv_diff_status.
    ARCHITECT(
            Set.of(
                    "cv_stats", "cv_health",
                    "cv_list_projects", "cv_find_project",
                    "cv_search", "cv_context", "cv_explain", "cv_impact",
                    "cv_test_impact", "cv_path", "cv_trace",
                    "cv_communities", "cv_flows", "cv_rules", "cv_guard",
                    "cv_diff_start", "cv_diff_status",
                    "cv_service_links", "cv_db_impact",
                    "cv_job_status", "cv_jobs_list"
            ),
            Set.of(
                    "/api/health", "/api/stats", "/api/projects",
                    "/api/search", "/api/explain", "/api/impact", "/api/test-impact"
            )
    ),

    SECURITY(
            Set.of(
                    "cv_audit", "cv_guard", "cv_rules", "cv_health", "cv_stats",
                    "cv_list_projects", "cv_find_project",
                    "cv_job_status", "cv_jobs_list"
            ),
            Set.of("/api/health", "/api/stats", "/api/audit", "/api/guard", "/api/rules")
    ),

    PM(
            Set.of(
                    "cv_stats", "cv_health",
                    "cv_list_projects", "cv_find_project",
                    "cv_onboard", "cv_changes", "cv_wiki",
                    "cv_job_status", "cv_jobs_list"
            ),
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
