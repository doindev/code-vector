package io.doindev.cvector.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/**
 * Short-circuits REST requests that would otherwise NPE inside a controller because the
 * workspace has no active project (no entry in {@code settings.json projects.*} or no
 * {@code activeProject} field). Returns a clean 424 (Failed Dependency) JSON payload
 * pointing the caller at the right onboarding tool instead.
 *
 * <p>Without this, every per-project endpoint (~20 controllers across the rest module)
 * would have to repeat its own null-guard. The interceptor runs ahead of dispatch, so
 * the controllers can keep their existing "assume projectId is non-null" code paths
 * without surfacing a generic 500 when the workspace is empty.
 *
 * <p>Exempt paths are the ones whose value doesn't depend on a project:
 * health/diagnostics endpoints, the project catalog (/api/projects), settings, the
 * dashboard SPA's own REST surface (/api/dashboard/**), MCP transport (/sse, /mcp),
 * and the SPA static assets (/dashboard/**).
 */
public class ActiveProjectRequiredInterceptor implements HandlerInterceptor {

    private static final Set<String> EXEMPT_EXACT = Set.of(
            "/api/health",
            "/api/projects",
            "/api/doctor",
            "/api/settings"
    );

    private static final String[] EXEMPT_PREFIXES = new String[] {
            "/api/dashboard/",
            "/api/settings/",
            "/sse",
            "/mcp",
            "/dashboard/",
            "/error"
    };

    private final ActiveProject project;

    public ActiveProjectRequiredInterceptor(ActiveProject project) {
        this.project = project;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (project.projectId() != null) return true;
        String path = request.getRequestURI();
        if (isExempt(path)) return true;
        response.setStatus(424); // Failed Dependency
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"no active project\","
                        + "\"message\":\"This endpoint requires a registered project. Call cv_add_project / cv_onboard_project from MCP, "
                        + "or run `cvector init` from your codebase directory.\","
                        + "\"path\":\"" + path + "\"}");
        return false;
    }

    private static boolean isExempt(String path) {
        if (path == null) return true;
        if (EXEMPT_EXACT.contains(path)) return true;
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
