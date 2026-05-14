package io.doindev.cvector.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background OSV.dev vulnerability scanner. Mirrors what the CLI {@code cvector audit} does,
 * but exposes results asynchronously so the dashboard can fire a scan, poll for status, and
 * render findings without blocking a request thread for the 5-30 seconds OSV typically takes.
 *
 * <p>Bounded parallelism keeps wall-clock low without hammering OSV — 8 in flight is enough
 * to amortise the per-call latency for the 30-60 deps a typical Maven graph has, and well
 * under any documented rate limit. Worker threads are daemon-flagged so an in-flight scan
 * never blocks JVM shutdown. Results are cached in a single {@link AtomicReference} so
 * concurrent {@code GET /api/audit} calls are lock-free.
 */
@Service
@ConditionalOnWebApplication
public class AuditService {

    private static final String OSV_URL = "https://api.osv.dev/v1/query";
    private static final int CONCURRENCY = 8;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final GraphStore store;
    private final ActiveProject project;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<State> state = new AtomicReference<>(State.idle());

    public AuditService(GraphStore restGraphStore, ActiveProject activeProject) {
        this.store = restGraphStore;
        this.project = activeProject;
    }

    public Map<String, Object> snapshot() {
        State s = state.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.status);
        if (s.startedAt != null) out.put("startedAt", s.startedAt.toString());
        if (s.finishedAt != null) out.put("finishedAt", s.finishedAt.toString());
        out.put("dependenciesInGraph", s.dependenciesInGraph);
        out.put("scanned", s.scanned);
        out.put("errors", s.errors);
        out.put("findings", s.findings);
        if (s.message != null) out.put("message", s.message);
        return out;
    }

    /**
     * Trigger a scan. No-op if one is already in flight (returns the current snapshot so
     * the caller sees we're already running). Otherwise spawns a virtual-thread worker and
     * returns immediately with the {@code running} state.
     */
    public synchronized Map<String, Object> startScan() {
        State current = state.get();
        if ("running".equals(current.status)) return snapshot();
        State next = new State("running", Instant.now(), null, 0, 0, 0, List.of(), null);
        state.set(next);
        Thread t = new Thread(this::runScan, "audit-scan");
        t.setDaemon(true);
        t.start();
        return snapshot();
    }

    private void runScan() {
        State started = state.get();
        try {
            List<Map<String, Object>> deps = store.mavenDependencies(project.projectId());
            List<Map<String, Object>> findings = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger scanned = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
                Thread t = new Thread(r, "audit-osv");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Map<String, Object> d : deps) {
                    String groupId = strOf(d.get("groupId"));
                    String artifactId = strOf(d.get("artifactId"));
                    String version = strOf(d.get("version"));
                    if (groupId == null || artifactId == null) continue;
                    if (version == null || version.isBlank() || "null".equals(version) || version.contains("${")) continue;
                    futures.add(pool.submit(() -> {
                        scanned.incrementAndGet();
                        try {
                            queryOsv(groupId, artifactId, version, findings);
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) { try { f.get(); } catch (Exception ignored) { } }
            } finally {
                pool.shutdown();
            }

            state.set(new State("done", started.startedAt, Instant.now(),
                    deps.size(), scanned.get(), errors.get(), List.copyOf(findings), null));
        } catch (Exception e) {
            state.set(new State("error", started.startedAt, Instant.now(),
                    0, 0, 0, List.of(), e.getMessage()));
        }
    }

    private void queryOsv(String groupId, String artifactId, String version,
                          List<Map<String, Object>> findings) throws Exception {
        String body = String.format(
                "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"%s:%s\"},\"version\":\"%s\"}",
                groupId, artifactId, version);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(OSV_URL))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("OSV " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode vulns = root.path("vulns");
        if (!vulns.isArray() || vulns.isEmpty()) return;
        String coord = groupId + ":" + artifactId + ":" + version;
        for (JsonNode v : vulns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dependency", coord);
            row.put("id", v.path("id").asText());
            row.put("summary", v.path("summary").asText(""));
            row.put("severity", primarySeverity(v));
            row.put("severityScore", primaryScore(v));
            findings.add(row);
        }
    }

    private static String primarySeverity(JsonNode vuln) {
        JsonNode sev = vuln.path("severity");
        if (sev.isArray() && !sev.isEmpty()) {
            String type = sev.get(0).path("type").asText("");
            String score = sev.get(0).path("score").asText("");
            return type + (score.isEmpty() ? "" : ":" + score);
        }
        JsonNode db = vuln.path("database_specific");
        if (!db.isMissingNode()) {
            String s = db.path("severity").asText("");
            if (!s.isEmpty()) return s;
        }
        return "unknown";
    }

    /**
     * Rough numeric severity for sorting / colouring. OSV's {@code severity[0].score} is
     * typically a CVSS vector string ({@code "CVSS:3.1/AV:N/..."}) with no embedded
     * numeric score, so we look at {@code database_specific.severity} first (the word form
     * GHSA populates) before falling back to a vector-string heuristic. Returns 0 only when
     * neither is present.
     */
    private static double primaryScore(JsonNode vuln) {
        JsonNode db = vuln.path("database_specific");
        String word = db.isMissingNode() ? "" : db.path("severity").asText("");
        double fromWord = switch (word.toUpperCase()) {
            case "CRITICAL" -> 9.5;
            case "HIGH" -> 7.5;
            case "MODERATE", "MEDIUM" -> 5.0;
            case "LOW" -> 2.5;
            default -> 0.0;
        };
        if (fromWord > 0) return fromWord;

        JsonNode sev = vuln.path("severity");
        if (sev.isArray() && !sev.isEmpty()) {
            String score = sev.get(0).path("score").asText("");
            // Numeric score (rare in OSV but possible).
            try { return Double.parseDouble(score); } catch (NumberFormatException ignore) { }
            // CVSS vector heuristic: rate severe vectors high. C:H/I:H/A:H => critical, single
            // H => high, otherwise medium. This is intentionally rough -- the dashboard's
            // severity bucket only needs a stable ordering, not a precise CVSS score.
            int hImpact = countOccurrences(score, ":H");
            if (hImpact >= 3) return 9.0;
            if (hImpact >= 1) return 7.0;
            if (score.startsWith("CVSS")) return 5.0;
        }
        return 0.0;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    private static String strOf(Object o) { return o == null ? null : o.toString(); }

    /** Immutable snapshot. {@code status} is one of {@code idle|running|done|error}. */
    private record State(
            String status,
            Instant startedAt,
            Instant finishedAt,
            int dependenciesInGraph,
            int scanned,
            int errors,
            List<Map<String, Object>> findings,
            String message
    ) {
        static State idle() { return new State("idle", null, null, 0, 0, 0, List.of(), null); }
    }
}
