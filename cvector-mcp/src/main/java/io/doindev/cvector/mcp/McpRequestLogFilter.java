package io.doindev.cvector.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every HTTP request hitting the MCP transport endpoints ({@code /sse} and
 * {@code /mcp**}) with method, path, response status, and the sessionId query param
 * when present. Without this, the only signal an external MCP agent failure leaves
 * behind is a Spring AI internal exception like
 * {@code WebMvcSseServerTransportProvider: Session not found: <uuid>} — which doesn't
 * say WHICH POST or WHICH session was rejected, making the "stale session" pattern
 * hard to diagnose.
 *
 * <p>Logs at INFO so it's visible in the default log config without bumping the
 * cvector-mcp logger level. SSE GET requests log on completion (they live the full
 * lifetime of the async session), POSTs log inline.
 *
 * <p>Profile-gated to {@code mcp} so non-MCP boots (CLI commands) don't pay for the
 * filter chain entry.
 */
@Component
@Profile("mcp")
@ConditionalOnWebApplication
public class McpRequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpRequestLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        return !(path.equals("/sse") || path.startsWith("/mcp"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String sessionId = request.getParameter("sessionId");
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            // 404 with "Session not found" is the diagnostic signal we want loudest.
            String suffix = (status == 404 && sessionId != null) ? "  [STALE-SESSION]" : "";
            log.info("mcp {} {} -> {} ({} ms) sessionId={}{}",
                    method, path, status, elapsedMs,
                    sessionId == null ? "<none>" : sessionId, suffix);
        }
    }
}
