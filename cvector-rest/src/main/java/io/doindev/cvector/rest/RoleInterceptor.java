package io.doindev.cvector.rest;

import io.doindev.cvector.core.CvectorRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

public class RoleInterceptor implements HandlerInterceptor {

    private final CvectorRole role;

    public RoleInterceptor(CvectorRole role) {
        this.role = role;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api")) return true;
        if (role.allowsRestPath(path)) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"forbidden\",\"role\":\"" + role.name().toLowerCase() + "\",\"path\":\""
                        + path.replace("\"", "\\\"") + "\"}"
        );
        return false;
    }
}
