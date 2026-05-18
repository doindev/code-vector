package io.doindev.cvector.mcp;

import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.config.CvectorConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Transparent localhost recovery for stale MCP session ids. When cvector restarts, every
 * client that held an {@code Mcp-Session-Id} from the prior run now carries a session id
 * the server doesn't recognise — Spring AI returns 404 "Session not found" on the next
 * request and most clients (Eclipse Copilot, in particular) require a close + reopen to
 * recover. On a loopback bind ({@code 127.0.0.1} / {@code ::1}) that's both unnecessary
 * friction AND safe to fix: the only callers are local processes the user already trusts.
 *
 * <p>The filter:
 * <ol>
 *   <li>Skips anything that isn't {@code /mcp} or {@code /mcp/message} — the dashboard UI
 *       talks to {@code /api/*} and never carries an {@code Mcp-Session-Id}, so it's
 *       invisible to this code path regardless of config.</li>
 *   <li>Skips off-loopback requests (a safety floor — accepting arbitrary client session
 *       ids on the network would be a replay vector).</li>
 *   <li>For a localhost request whose {@code Mcp-Session-Id} the server has never seen
 *       before, internally POSTs an {@code initialize} + {@code notifications/initialized}
 *       to {@code http://127.0.0.1:<port>/mcp} to mint a fresh server-side session,
 *       remembers the {@code clientId → serverId} mapping (with TTL), and rewrites the
 *       incoming request's {@code Mcp-Session-Id} header to the server's id. Subsequent
 *       requests from the same client with the same old id get the same rewrite — Eclipse
 *       Copilot never notices anything happened.</li>
 * </ol>
 *
 * <p><b>Loop prevention.</b> The filter's own internal initialize/initialized POSTs hit
 * the same {@code /mcp} endpoint and would re-enter this filter. They carry a sentinel
 * header {@code X-Cvector-Recovery: 1}; the filter's {@link #shouldNotFilter} bails out
 * when it sees that header, so the internal calls flow straight through to Spring AI.
 *
 * <p><b>Validity tracking.</b> The filter doesn't peek into Spring AI's session table
 * (private and version-fragile). Instead it conservatively assumes a session is "unknown"
 * if it isn't already in our client→server mapping. The first request after restart hits
 * the recovery path, populates the mapping, and every subsequent request short-circuits
 * through the cache.
 */
@Component
@Profile("mcp")
@ConditionalOnWebApplication
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalhostSessionRecoveryFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostSessionRecoveryFilter.class);

    /** Header carried by the filter's own internal calls to break the recursion loop. */
    private static final String INTERNAL_HEADER = "X-Cvector-Recovery";

    private final CvectorConfigService configService;
    private final int localPort;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    /**
     * Cached config — null until the first request triggers a load, then refreshed via
     * {@link #reloadConfig()} on every miss so a `/api/settings` PUT picks up without restart.
     * The fields we read (enabled, ttl) are tiny; re-reading from disk on miss is cheap.
     */
    private volatile CvectorConfig.McpConfig.LocalhostSessionRecovery cachedConfig;
    private volatile Instant cachedConfigUntil = Instant.EPOCH;

    /**
     * Live mapping of {@code clientId → recovered server id + expiry}. Entries are
     * inserted on a successful synthetic initialize and re-used until they hit their
     * TTL. Manual cleanup happens lazily on the next lookup; no scheduled thread.
     */
    private final Map<String, Mapping> clientToServer = new ConcurrentHashMap<>();

    /**
     * Server-side session ids we've seen Spring AI assign (by snooping the
     * {@code Mcp-Session-Id} response header on {@code initialize} responses) or that we
     * minted ourselves during recovery. Requests carrying an id in this set are real
     * sessions — pass through unchanged, skip the recovery probe. Without this guard,
     * every legitimate first-after-initialize tool call would land in {@code lookupOrRecover}
     * and synthesise a redundant server session, leaking one shadow session per real client.
     */
    private final Set<String> knownGoodSessions = ConcurrentHashMap.newKeySet();

    public LocalhostSessionRecoveryFilter(CvectorConfigService configService,
                                          @Value("${server.port:2969}") int localPort) {
        this.configService = configService;
        this.localPort = localPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 1. The filter's own internal calls always bypass to avoid infinite recursion.
        if (request.getHeader(INTERNAL_HEADER) != null) return true;
        // 2. Only MCP POST transport endpoints matter. Dashboard /api/*, /dashboard/*,
        //    /actuator, etc. are not in scope. The SSE GET stream endpoint at /sse mints
        //    a fresh session per connection (no client-supplied sessionId), so it's a
        //    no-op here too.
        String path = request.getRequestURI();
        if (path == null) return true;
        if (!(path.equals("/mcp") || path.equals("/mcp/message"))) return true;
        // 3. We only intercept POSTs. GET /mcp is the Streamable HTTP optional stream-back
        //    channel — Spring AI uses the Mcp-Session-Id on it the same way as POST, but
        //    rewriting it mid-stream would corrupt the SSE response. The trade-off:
        //    after a server restart, a client's stale GET /mcp stream connection will 404
        //    and the client should reopen it (standard streaming-client behaviour). The
        //    POST channel — where the actual tool calls live — is what we recover.
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CvectorConfig.McpConfig.LocalhostSessionRecovery cfg = effectiveConfig();
        if (cfg == null || !Boolean.TRUE.equals(cfg.enabled())) {
            chain.doFilter(request, response);
            return;
        }
        if (!isLoopback(request)) {
            chain.doFilter(request, response);
            return;
        }
        boolean isSse = "/mcp/message".equals(request.getRequestURI());
        String clientSessionId = isSse
                ? request.getParameter("sessionId")        // SSE: ?sessionId=<uuid>
                : request.getHeader("Mcp-Session-Id");      // Streamable: Mcp-Session-Id header
        if (clientSessionId == null || clientSessionId.isBlank()) {
            // No session id on the request — for Streamable HTTP this is the `initialize`
            // call (the step that mints a session); for SSE it's an invalid POST that Spring
            // AI will reject anyway. For initialize, snoop the response's Mcp-Session-Id
            // header so we can mark that session as known-good and skip the recovery path
            // for its follow-up requests.
            SessionIdCapturingResponse wrapper = new SessionIdCapturingResponse(response);
            chain.doFilter(request, wrapper);
            String minted = wrapper.capturedSessionId;
            if (minted != null && !minted.isBlank()) knownGoodSessions.add(minted);
            return;
        }
        // Real sessions Spring AI just minted (and live recovered sessions we minted
        // ourselves) live in knownGoodSessions — short-circuit the recovery probe for them
        // so legitimate first-tool-call traffic doesn't get a needless ghost session.
        if (knownGoodSessions.contains(clientSessionId)) {
            chain.doFilter(request, response);
            return;
        }
        // Either we've recovered this client before (use the cached mapping) or we haven't
        // (synthesize one now). Map is keyed on the CLIENT's id so repeat calls find the
        // same server-side session id.
        Mapping mapping = lookupOrRecover(clientSessionId, cfg, isSse);
        if (mapping == null) {
            // Recovery failed (e.g. internal initialize threw). Pass through so Spring AI's
            // own 404 still surfaces — at least the user sees the original error.
            chain.doFilter(request, response);
            return;
        }
        if (mapping.serverId.equals(clientSessionId)) {
            // The "recovery" produced an id identical to the client's — unlikely but harmless.
            // Pass through unchanged.
            chain.doFilter(request, response);
            return;
        }
        // Rewrite the session id and forward. For Streamable HTTP we rewrite the
        // Mcp-Session-Id header; for SSE we rewrite the `?sessionId=` query param. We don't
        // touch the body, other headers, or any other part of the request.
        chain.doFilter(isSse ? rewrapSse(request, mapping.serverId)
                              : rewrap(request, mapping.serverId), response);
    }

    /**
     * Servlet response wrapper that snoops on {@code Mcp-Session-Id} header writes — Spring
     * AI's Streamable HTTP transport calls {@code setHeader("Mcp-Session-Id", …)} on the
     * initialize response. We just remember the value; everything else passes through.
     */
    private static final class SessionIdCapturingResponse extends HttpServletResponseWrapper {
        String capturedSessionId;

        SessionIdCapturingResponse(HttpServletResponse delegate) { super(delegate); }

        @Override
        public void setHeader(String name, String value) {
            if ("Mcp-Session-Id".equalsIgnoreCase(name)) capturedSessionId = value;
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Mcp-Session-Id".equalsIgnoreCase(name) && capturedSessionId == null) {
                capturedSessionId = value;
            }
            super.addHeader(name, value);
        }
    }

    private Mapping lookupOrRecover(String clientSessionId,
                                    CvectorConfig.McpConfig.LocalhostSessionRecovery cfg,
                                    boolean isSse) {
        long ttlMinutes = cfg.ttlMinutes() == null || cfg.ttlMinutes() <= 0 ? 60 : cfg.ttlMinutes();
        Instant now = Instant.now();
        Mapping cached = clientToServer.get(clientSessionId);
        if (cached != null && cached.expiresAt.isAfter(now) && cached.sse == isSse) return cached;
        // Compute-under-lock so simultaneous incoming requests for the same client only
        // create ONE recovered server session — otherwise N concurrent first-after-restart
        // tool calls would each kick off their own initialize.
        return clientToServer.compute(clientSessionId, (key, existing) -> {
            if (existing != null && existing.expiresAt.isAfter(now) && existing.sse == isSse) {
                return existing;
            }
            if (existing != null) existing.close();  // transport changed — tear down old stream
            Synthesized fresh = isSse ? synthesizeSseSession() : synthesizeStreamableSession();
            if (fresh == null) return null;
            // The session we just minted IS a real Spring AI session — register it as
            // known-good so a future request rewritten to its id short-circuits past this
            // recovery path.
            knownGoodSessions.add(fresh.serverId());
            log.info("recovered stale localhost session ({}) client={} → server={}",
                    isSse ? "sse" : "streamable", clientSessionId, fresh.serverId());
            return new Mapping(fresh.serverId(), now.plus(Duration.ofMinutes(ttlMinutes)),
                    isSse, fresh.streamCloser());
        });
    }

    /**
     * Synthesise a fresh server-side session for the Streamable HTTP transport via an
     * internal {@code initialize} + {@code notifications/initialized} on {@code /mcp}.
     * Returns a {@link Synthesized} with the new session id and a no-op stream-closer
     * (there's no long-lived stream to manage in this transport).
     */
    private Synthesized synthesizeStreamableSession() {
        String id = synthesizeServerSession();
        return id == null ? null : new Synthesized(id, () -> {});
    }

    /**
     * Synthesise a fresh server-side session for the SSE (2024-11-05) transport. The MCP
     * SSE protocol couples the session to a long-lived {@code GET /sse} stream — Spring AI
     * evicts the session the instant that stream closes. So we have to open the stream
     * <em>and keep it open</em> for the lifetime of the recovered mapping, draining
     * incoming events on a daemon thread.
     *
     * <p><b>Caveat operators should know about:</b> the JSON-RPC responses that follow
     * the client's POSTs now land on <em>our</em> synthetic stream, not on the client's
     * own (which died with the previous cvector process). For fire-and-forget calls
     * (e.g. {@code notifications/initialized}) this is fine — the client doesn't expect
     * a response. For request/response calls (e.g. {@code tools/call}) the response is
     * silently absorbed; the client either times out or, more commonly, the client's own
     * EventSource reconnects in the background, gets a fresh session, and the recovery
     * mapping becomes vestigial. Most well-behaved SSE clients auto-reconnect (per the
     * HTML5 EventSource spec), which is why this is a "best-effort" recovery — the
     * Streamable HTTP path is the one that genuinely needs the rewrite to function.
     */
    private Synthesized synthesizeSseSession() {
        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + localPort + "/sse"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .header(INTERNAL_HEADER, "1")
                .GET()
                .build();
        HttpResponse<Stream<String>> resp;
        try {
            resp = httpClient.send(sseReq, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            log.warn("recovery: failed to open synthetic /sse stream: {}", e.getMessage());
            return null;
        }
        if (resp.statusCode() != 200) {
            log.warn("recovery: synthetic /sse returned http={}", resp.statusCode());
            return null;
        }
        Stream<String> body = resp.body();
        Iterator<String> iter = body.iterator();

        // Spring AI emits the endpoint event as the first event on the stream. Format:
        //   event: endpoint
        //   data: /mcp/message?sessionId=<uuid>
        //
        //   (blank line terminator)
        String endpointPath = readEndpointEvent(iter);
        if (endpointPath == null) {
            log.warn("recovery: synthetic /sse stream closed without endpoint event");
            try { body.close(); } catch (RuntimeException ignored) { }
            return null;
        }
        String serverSessionId = extractSessionFromQuery(endpointPath);
        if (serverSessionId == null) {
            log.warn("recovery: endpoint event had no sessionId: {}", endpointPath);
            try { body.close(); } catch (RuntimeException ignored) { }
            return null;
        }
        // POST initialize + notifications/initialized to the per-session POST endpoint.
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05",
                           "capabilities":{"tools":{},"resources":{},"prompts":{}},
                           "clientInfo":{"name":"cvector-recovery","version":"1.0"}}}
                """;
        try {
            HttpRequest init = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + endpointPath))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(INTERNAL_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build();
            httpClient.send(init, HttpResponse.BodyHandlers.discarding());
            HttpRequest ready = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + endpointPath))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(INTERNAL_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                    .build();
            httpClient.send(ready, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("recovery: failed to drive initialize on synthetic /sse: {}", e.getMessage());
            try { body.close(); } catch (RuntimeException ignored) { }
            return null;
        }
        // Drain remaining events on a daemon thread to keep the underlying TCP read pump
        // ticking — Spring AI evicts the session if the SSE stream goes silent / dead.
        Thread drainer = new Thread(() -> {
            try {
                while (iter.hasNext()) {
                    iter.next();  // discard; the client's own (separate) stream is where
                                   // these events would land for a real flow
                }
            } catch (RuntimeException ignored) {
                // Stream closed externally (TTL eviction, JVM shutdown) — expected.
            }
        }, "cvector-sse-recovery-drain-" + serverSessionId.substring(0, Math.min(8, serverSessionId.length())));
        drainer.setDaemon(true);
        drainer.start();

        // Cleanup callback closes the body stream, which unblocks the iterator in the
        // daemon thread and lets it exit. Spring AI then evicts the recovered session.
        return new Synthesized(serverSessionId, () -> {
            try { body.close(); } catch (RuntimeException ignored) { }
        });
    }

    /**
     * Read SSE lines off {@code iter} until a complete {@code event: endpoint} arrives.
     * Returns the endpoint path (the {@code data:} value), or {@code null} if the stream
     * closed before the event landed. Handles the standard SSE framing: `event:` /
     * `data:` lines separated by blank lines.
     */
    private static String readEndpointEvent(Iterator<String> iter) {
        String currentEvent = null;
        StringBuilder dataBuf = new StringBuilder();
        long deadline = System.currentTimeMillis() + 10_000L;
        try {
            while (iter.hasNext()) {
                if (System.currentTimeMillis() > deadline) return null;
                String line = iter.next();
                if (line.isEmpty()) {
                    if ("endpoint".equals(currentEvent) && dataBuf.length() > 0) {
                        return dataBuf.toString().trim();
                    }
                    currentEvent = null;
                    dataBuf.setLength(0);
                } else if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (dataBuf.length() > 0) dataBuf.append('\n');
                    dataBuf.append(line.substring(5).trim());
                }
                // Other SSE fields (id:, retry:, comments) are irrelevant for the
                // endpoint event — silently skip.
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String extractSessionFromQuery(String pathWithQuery) {
        int q = pathWithQuery.indexOf('?');
        if (q < 0) return null;
        for (String part : pathWithQuery.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "sessionId".equals(part.substring(0, eq))) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * Issue an internal {@code initialize} + {@code notifications/initialized} pair so
     * Spring AI mints a fresh server-side session. Returns the newly-assigned session id
     * extracted from the initialize response's {@code Mcp-Session-Id} header, or
     * {@code null} when anything goes sideways (network error, 4xx/5xx response, missing
     * header). The carrier {@link #INTERNAL_HEADER} marks both calls as internal so this
     * filter doesn't try to recover its own traffic recursively.
     */
    private String synthesizeServerSession() {
        try {
            String initBody = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize",
                     "params":{"protocolVersion":"2025-03-26",
                               "capabilities":{"tools":{},"resources":{},"prompts":{}},
                               "clientInfo":{"name":"cvector-recovery","version":"1.0"}}}
                    """;
            HttpRequest init = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + "/mcp"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header(INTERNAL_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build();
            HttpResponse<String> initResp = httpClient.send(init, HttpResponse.BodyHandlers.ofString());
            if (initResp.statusCode() != 200) {
                log.warn("recovery: internal initialize returned http={} body={}",
                        initResp.statusCode(), trim(initResp.body()));
                return null;
            }
            String newServerId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (newServerId == null || newServerId.isBlank()) {
                log.warn("recovery: internal initialize returned no Mcp-Session-Id header");
                return null;
            }
            // Spring AI gates every non-initialize request on `notifications/initialized`
            // having been observed for the session. Fire it now so the very first real
            // tool call from the client succeeds rather than hangs.
            HttpRequest ready = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + "/mcp"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Mcp-Session-Id", newServerId)
                    .header(INTERNAL_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                    .build();
            httpClient.send(ready, HttpResponse.BodyHandlers.discarding());
            return newServerId;
        } catch (Exception e) {
            log.warn("recovery: failed to synthesize server session: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Loopback check. {@code request.getRemoteAddr()} returns the peer's IP as the kernel
     * sees it — for any localhost connection that's {@code 127.0.0.1} or {@code ::1}
     * (sometimes serialised as {@code 0:0:0:0:0:0:0:1}). Anything else (LAN clients, Docker
     * bridge networks, …) gets the plain Spring-AI 404 behaviour.
     */
    private static boolean isLoopback(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        if (addr == null) return false;
        return addr.equals("127.0.0.1") || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * Read the recovery section from settings.json, with a 30 s cache so we don't re-load
     * the file on every request. {@code null} means we couldn't load (no workspace, parse
     * failure) — caller treats that as "feature disabled" and lets Spring AI's plain 404
     * behaviour stand.
     */
    private CvectorConfig.McpConfig.LocalhostSessionRecovery effectiveConfig() {
        Instant now = Instant.now();
        if (cachedConfig != null && cachedConfigUntil.isAfter(now)) return cachedConfig;
        return reloadConfig();
    }

    private synchronized CvectorConfig.McpConfig.LocalhostSessionRecovery reloadConfig() {
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            Path root = configService.findConfigRoot(cwd);
            if (root == null) return null;
            CvectorConfig cfg = configService.load(root);
            CvectorConfig.McpConfig.LocalhostSessionRecovery recovery =
                    cfg.mcpOrDefault().localhostSessionRecoveryOrDefault();
            this.cachedConfig = recovery;
            this.cachedConfigUntil = Instant.now().plus(Duration.ofSeconds(30));
            return recovery;
        } catch (IOException | RuntimeException e) {
            log.debug("recovery: could not load settings.json ({}); disabling for this tick",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Wrap the request so {@code request.getHeader("Mcp-Session-Id")} returns the
     * server-side id rather than the client's stale one. Spring AI's
     * {@code WebMvcStreamableServerTransportProvider} reads the header by name; nothing
     * else needs to be tampered with.
     */
    private static HttpServletRequest rewrap(HttpServletRequest original, String newSessionId) {
        return new HttpServletRequestWrapper(original) {
            @Override
            public String getHeader(String name) {
                if ("Mcp-Session-Id".equalsIgnoreCase(name)) return newSessionId;
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if ("Mcp-Session-Id".equalsIgnoreCase(name)) {
                    return Collections.enumeration(java.util.List.of(newSessionId));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> names = new HashSet<>();
                Enumeration<String> orig = super.getHeaderNames();
                while (orig.hasMoreElements()) names.add(orig.nextElement());
                names.add("Mcp-Session-Id");
                return Collections.enumeration(names);
            }
        };
    }

    /**
     * Rewrap the SSE POST so {@code request.getParameter("sessionId")} and the query
     * string both reflect the recovered server id. Spring AI's
     * {@code WebMvcSseServerTransportProvider} reads the session id off the query
     * parameter — preserving everything else (other params, body, method, headers)
     * keeps the rest of the request intact.
     */
    private static HttpServletRequest rewrapSse(HttpServletRequest original, String newSessionId) {
        String encoded = URLEncoder.encode(newSessionId, StandardCharsets.UTF_8);
        String origQuery = original.getQueryString() == null ? "" : original.getQueryString();
        String newQuery = rewriteQueryStringSessionId(origQuery, encoded);
        Map<String, String[]> origParams = original.getParameterMap();
        Map<String, String[]> rewrittenParams = new java.util.HashMap<>(origParams);
        rewrittenParams.put("sessionId", new String[] { newSessionId });
        return new HttpServletRequestWrapper(original) {
            @Override
            public String getQueryString() { return newQuery; }

            @Override
            public String getParameter(String name) {
                if ("sessionId".equals(name)) return newSessionId;
                return super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                if ("sessionId".equals(name)) return new String[] { newSessionId };
                return super.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                return Collections.unmodifiableMap(rewrittenParams);
            }

            @Override
            public Enumeration<String> getParameterNames() {
                return Collections.enumeration(rewrittenParams.keySet());
            }
        };
    }

    private static String rewriteQueryStringSessionId(String query, String encodedNewId) {
        if (query.isBlank()) return "sessionId=" + encodedNewId;
        StringBuilder out = new StringBuilder(query.length() + encodedNewId.length());
        boolean replaced = false;
        for (String pair : query.split("&")) {
            if (out.length() > 0) out.append('&');
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if ("sessionId".equals(key)) {
                out.append("sessionId=").append(encodedNewId);
                replaced = true;
            } else {
                out.append(pair);
            }
        }
        if (!replaced) out.append("&sessionId=").append(encodedNewId);
        return out.toString();
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Per-client-session recovery state. {@link #close()} tears down any background
     * resources (SSE drain thread + the underlying TCP stream) — invoked when the TTL
     * expires or when a fresh recovery for the same client supersedes this one.
     */
    private static final class Mapping {
        final String serverId;
        final Instant expiresAt;
        final boolean sse;
        private final Runnable streamCloser;

        Mapping(String serverId, Instant expiresAt, boolean sse, Runnable streamCloser) {
            this.serverId = serverId;
            this.expiresAt = expiresAt;
            this.sse = sse;
            this.streamCloser = streamCloser;
        }

        void close() {
            try { streamCloser.run(); } catch (RuntimeException ignored) { }
        }
    }

    /** Output of a single synthesize-*-session call — the new server id plus how to tear it down. */
    private record Synthesized(String serverId, Runnable streamCloser) {}
}
