package io.doindev.cvector.rest;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaces Spring Boot's Whitelabel Error Page with a useful diagnostic surface and pipes
 * every error through the terminal as a printed block.
 *
 * <p>Why this exists: when a user runs {@code cvector dashboard -o} and the dashboard SPA
 * isn't on the classpath (typical when the build wasn't done with {@code -Pdashboard-ui}),
 * a request to {@code /dashboard/} falls through to Spring's default error handling, which
 * shows the cryptic "Whitelabel Error Page / Not found for /error" message and prints
 * nothing to the terminal. Operators couldn't tell whether the issue was a missing SPA, a
 * wrong port, a 404 on a typo'd path, or something deeper.
 *
 * <p>This controller fixes that on three fronts:
 * <ol>
 *   <li><strong>Browser response.</strong> Returns a self-contained HTML page (no external
 *       CSS/JS — works even when the SPA bundle is empty) with the request URI, status
 *       code, exception class + message, and a stack trace if one was captured. Includes
 *       a path-aware hint: requests under {@code /dashboard/} on a missing SPA suggest
 *       rebuilding with {@code -Pdashboard-ui}; other 4xx paths get a list of known-good
 *       endpoints to try next.</li>
 *   <li><strong>API response.</strong> Same error info as structured JSON when the caller
 *       prefers {@code application/json} — used by the dashboard's REST views and any
 *       external script polling the API.</li>
 *   <li><strong>Terminal log.</strong> Every {@code /error} hit prints a multi-line block
 *       to {@link System#err} including the full stack trace. With {@code <winConsole>}
 *       on the .exe and stdout/stderr inherited from PowerShell, the operator sees the
 *       failure live without having to open a log file.</li>
 * </ol>
 *
 * <p>Lives in {@code cvector-rest} so it's always on the classpath whenever the REST API
 * is — it's not gated behind the {@code dashboard-ui} profile (which would defeat the
 * point: the error page is most useful exactly when the dashboard module isn't there).
 */
@Controller
public class CvectorErrorController implements ErrorController {

    private static final String ERROR_PATH = "/error";

    /**
     * HTML branch: browsers (Accept: text/html) land here. Mirrors Spring Boot's
     * {@code BasicErrorController.errorHtml} signature so content negotiation
     * picks this when the client explicitly prefers HTML.
     */
    @RequestMapping(value = ERROR_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> errorHtml(HttpServletRequest request) {
        ErrorInfo info = collect(request);
        logToStderr(info);
        return ResponseEntity.status(info.status())
                .contentType(MediaType.TEXT_HTML)
                .body(renderHtml(info));
    }

    /**
     * Fallback branch with no {@code produces} filter — matches every other Accept
     * header (JSON, {@code * / *}, missing entirely). Without this, requests whose
     * Accept header doesn't include {@code text/html} fall through to Spring's
     * default 404 for {@code /error}, leaving the operator with no diagnostic.
     * Returns JSON because most non-browser callers (curl, the SPA's fetch, monitoring
     * scripts) want a structured payload.
     */
    @RequestMapping(value = ERROR_PATH)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> errorFallback(HttpServletRequest request) {
        ErrorInfo info = collect(request);
        logToStderr(info);
        return ResponseEntity.status(info.status()).body(renderJson(info));
    }

    /**
     * Read the standard servlet error attributes Spring populates on the request. Falls
     * back to status 500 and "Unknown" reason when an attribute is missing — happens for
     * direct-navigation to {@code /error} (e.g. a user typing it in the URL bar).
     */
    private static ErrorInfo collect(HttpServletRequest req) {
        Integer status = (Integer) req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        String path = (String) req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String message = (String) req.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Throwable exception = (Throwable) req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        int code = status != null ? status : 500;
        HttpStatus resolved = HttpStatus.resolve(code);
        String reason = resolved != null ? resolved.getReasonPhrase() : "Unknown";
        String excClass = exception != null ? exception.getClass().getName() : null;
        String excMessage = exception != null ? exception.getMessage() : null;
        String stack = exception != null ? stackToString(exception) : null;
        String effectiveMessage = (message != null && !message.isBlank()) ? message : excMessage;
        return new ErrorInfo(code, reason, path != null ? path : "(unknown path)",
                excClass, effectiveMessage, stack);
    }

    /**
     * Print a multi-line block to stderr. Spring Boot's default behaviour is to log at
     * WARN with a one-liner and bury the stack inside an internal logger; on the .exe
     * those logs disappear into the void because the console subsystem state hasn't
     * always inherited. Printing directly to {@code System.err} bypasses the log
     * framework so the operator sees the failure even when log config is minimal.
     */
    private static void logToStderr(ErrorInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("=== cvector dashboard error ").append(Instant.now()).append(" ===\n");
        sb.append("  status:    ").append(info.status()).append(' ').append(info.reason()).append('\n');
        sb.append("  path:      ").append(info.path()).append('\n');
        if (info.exception() != null) sb.append("  exception: ").append(info.exception()).append('\n');
        if (info.message() != null)   sb.append("  message:   ").append(info.message()).append('\n');
        if (info.stackTrace() != null) {
            sb.append("  stack trace:\n");
            for (String line : info.stackTrace().split("\\r?\\n")) {
                sb.append("    ").append(line).append('\n');
            }
        }
        sb.append("=== end error ===");
        System.err.println(sb);
    }

    /**
     * Render a self-contained HTML error page. Inline CSS, no external assets, so it
     * renders even when the SPA wasn't bundled (no static/dashboard/browser/ on the
     * classpath). Adds context-sensitive hints based on the failing path: /dashboard/*
     * misses get a "rebuild with -Pdashboard-ui" hint, others get a list of known-good
     * endpoints to try.
     */
    private static String renderHtml(ErrorInfo info) {
        String hint = hintFor(info);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<title>cvector — ").append(info.status()).append(' ').append(escape(info.reason())).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#1a1d20;color:#e9ecef;margin:0;padding:2rem;line-height:1.5}");
        sb.append(".wrap{max-width:1024px;margin:0 auto}");
        sb.append("h1{font-size:2rem;margin:0 0 .5rem;color:#ff7575}");
        sb.append("h2{font-size:1.1rem;margin:1.5rem 0 .5rem;color:#9ca8b4;text-transform:uppercase;letter-spacing:.05em;font-weight:600}");
        sb.append("code{background:#2a2d30;padding:.15rem .4rem;border-radius:.25rem;font-family:Consolas,Monaco,monospace;font-size:.9em}");
        sb.append("pre{background:#2a2d30;padding:1rem;border-radius:.4rem;overflow-x:auto;font-size:.85rem;line-height:1.45}");
        sb.append(".meta{background:#212529;border:1px solid #3a3e44;border-radius:.5rem;padding:.6rem 1rem;margin:0;font-family:Consolas,Monaco,monospace;font-size:.9rem}");
        sb.append(".meta-row{display:grid;grid-template-columns:9rem 1fr;gap:.5rem;padding:.3rem 0;border-bottom:1px solid #2a2d30}");
        sb.append(".meta-row:last-child{border-bottom:none}");
        sb.append(".meta-key{color:#9ca8b4}");
        sb.append(".hint{background:#1f2a3a;border-left:3px solid #5862e3;padding:.8rem 1rem;border-radius:.25rem;margin-top:1rem}");
        sb.append(".hint a{color:#8a91f0;text-decoration:none}");
        sb.append(".hint a:hover{text-decoration:underline}");
        sb.append("details{margin-top:1rem}");
        sb.append("summary{cursor:pointer;color:#9ca8b4;padding:.3rem 0}");
        sb.append("summary:hover{color:#e9ecef}");
        sb.append("</style></head><body><div class=\"wrap\">");
        sb.append("<h1>").append(info.status()).append(' ').append(escape(info.reason())).append("</h1>");
        sb.append("<div class=\"meta\">");
        sb.append("<div class=\"meta-row\"><span class=\"meta-key\">request:</span><span>").append(escape(info.path())).append("</span></div>");
        if (info.exception() != null) {
            sb.append("<div class=\"meta-row\"><span class=\"meta-key\">exception:</span><span>").append(escape(info.exception())).append("</span></div>");
        }
        if (info.message() != null) {
            sb.append("<div class=\"meta-row\"><span class=\"meta-key\">message:</span><span>").append(escape(info.message())).append("</span></div>");
        }
        sb.append("</div>");
        if (hint != null) {
            sb.append("<div class=\"hint\">").append(hint).append("</div>");
        }
        if (info.stackTrace() != null) {
            sb.append("<details><summary>stack trace</summary><pre>").append(escape(info.stackTrace())).append("</pre></details>");
        }
        sb.append("<h2>known endpoints</h2>");
        sb.append("<ul>");
        sb.append("<li><a href=\"/api/health\" style=\"color:#8a91f0\">/api/health</a> — backend + project info</li>");
        sb.append("<li><a href=\"/api/stats\" style=\"color:#8a91f0\">/api/stats</a> — node + edge counts</li>");
        sb.append("<li><a href=\"/api/projects\" style=\"color:#8a91f0\">/api/projects</a> — workspace projects</li>");
        sb.append("<li><a href=\"/dashboard/\" style=\"color:#8a91f0\">/dashboard/</a> — Angular SPA (requires <code>-Pdashboard-ui</code> build)</li>");
        sb.append("</ul>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static Map<String, Object> renderJson(ErrorInfo info) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", info.status());
        body.put("error", info.reason());
        body.put("path", info.path());
        if (info.exception() != null) body.put("exception", info.exception());
        if (info.message() != null)   body.put("message", info.message());
        if (info.stackTrace() != null) body.put("stackTrace", info.stackTrace());
        return body;
    }

    /**
     * Path-aware hint shown above the stack trace. Specific cases first, then a generic
     * "try one of these" pointer. Returns {@code null} when there's nothing useful to add
     * (so we don't pad the page with empty boxes).
     */
    private static String hintFor(ErrorInfo info) {
        String path = info.path();
        if (path == null) return null;
        if (info.status() == 404 && path.startsWith("/dashboard")) {
            return "The dashboard SPA isn't on the classpath. The cvector .exe / fat-jar "
                    + "needs to be built with <code>-Pdashboard-ui</code> (or the install "
                    + "script run without <code>CVECTOR_SKIP_DASHBOARD=1</code>) to bundle "
                    + "the Angular UI. REST endpoints under <code>/api/**</code> work "
                    + "independently and don't need this profile.";
        }
        if (info.status() == 404) {
            return "No handler matched <code>" + escape(path) + "</code>. cvector's REST "
                    + "API lives under <code>/api/**</code> and the SPA under "
                    + "<code>/dashboard/**</code>. Check the spelling, the active project, "
                    + "or hit <a href=\"/api/health\">/api/health</a> to verify the server "
                    + "is reachable.";
        }
        if (info.status() >= 500) {
            return "The server caught an unhandled exception. The terminal that started "
                    + "<code>cvector dashboard</code> has the full stack trace; check "
                    + "there for the line and library involved. Common causes: a "
                    + "broken Kuzu database (delete <code>~/.cvector/kuzu-data/&lt;projectId&gt;/</code> "
                    + "and rescan), a stale schema (run <code>cvector scan</code> again), "
                    + "or a Cypher query referencing a removed property.";
        }
        return null;
    }

    private static String stackToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '&'  -> sb.append("&amp;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private record ErrorInfo(int status, String reason, String path, String exception, String message, String stackTrace) {}
}
