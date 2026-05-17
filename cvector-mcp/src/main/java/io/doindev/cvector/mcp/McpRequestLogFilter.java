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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs every HTTP request hitting the MCP transport endpoints ({@code /sse} and
 * {@code /mcp**}) with method, path, response status, and the sessionId query param
 * when present. Without this, the only signal an external MCP agent failure leaves
 * behind is a Spring AI internal exception like
 * {@code WebMvcSseServerTransportProvider: Session not found: <uuid>} — which doesn't
 * say WHICH POST or WHICH session was rejected, making the "stale session" pattern
 * hard to diagnose.
 *
 * <p><b>Also serialises POSTs per session.</b> Spring AI 1.0.0's
 * {@code WebMvcMcpSessionTransport.sendMessage} writes responses through a shared
 * {@code SseBuilder} bound to the SSE GET request. The builder is not thread-safe —
 * two POSTs that complete concurrently both call {@code sseBuilder.data(...)} on the
 * same instance, corrupting the underlying NIO buffer state. In the field that
 * manifested as
 * {@code IllegalArgumentException: newPosition > limit: (262144 > 1871)} thrown deep
 * in {@code IOUtil.write} and a stuck MCP session that timed out every subsequent
 * call. This filter holds a per-sessionId monitor across {@code chain.doFilter(...)}
 * so the SSE-side write completes before the next POST for that session begins.
 *
 * <p>Trade-off: parallel POSTs to a single MCP session lose their parallelism here.
 * In practice every individual tool call returns in milliseconds (or returns an
 * immediate jobId envelope for the {@code async:true} path), so the user-visible
 * impact is a few extra ms of serialisation. The win is that the SSE write path
 * stops getting corrupted under concurrent agent traffic, which was the regression
 * the testing agent surfaced. The async/job-status pattern remains the right answer
 * for genuinely long-running work; this filter just keeps the response transport
 * correct when an LLM fans out multiple short tool calls.
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

    /**
     * One {@link Object} monitor per MCP sessionId. {@link ConcurrentHashMap#computeIfAbsent}
     * creates the lock lazily on first POST for the session and reuses it for every
     * subsequent POST. Entries leak when the SSE session closes — minor memory cost (a
     * single Object reference per ever-seen session) but no correctness implication.
     * Could be cleaned up via a {@code @WeakHashMap} or via a sessions-closed event from
     * Spring AI; not worth the complexity until sessions-per-JVM gets large.
     */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

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
        // Only serialise the POST path — the SSE GET is the long-lived response stream and
        // serialising it would prevent any concurrency at all. The race only happens when
        // two POSTs both try to write back through the SAME sseBuilder for the same
        // session, so the lock is keyed by sessionId and scoped to POST handling.
        boolean serialise = "POST".equals(method) && sessionId != null && path != null && path.startsWith("/mcp");
        try {
            if (serialise) {
                Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
                synchronized (lock) {
                    chain.doFilter(request, response);
                }
            } else {
                chain.doFilter(request, response);
            }
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
