package io.doindev.cvector.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "audit", description = "Dependency vulnerability audit (OSV) cross-referenced with the graph.")
public class AuditCommand implements Callable<Integer> {

    private static final String OSV_URL = "https://api.osv.dev/v1/query";

    @Option(names = "--ci", description = "Exit non-zero if any vulnerabilities are found.")
    private boolean ci;

    @Option(names = "--timeout", description = "Per-request timeout in seconds (default 10).")
    private int timeoutSeconds = 10;

    private final CvectorRuntime runtime;

    public AuditCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);
            List<Map<String, Object>> deps = q.raw(
                    "MATCH (d:MavenDependency {projectId: $pid}) "
                            + "RETURN d.groupId AS groupId, d.artifactId AS artifactId, d.version AS version "
                            + "ORDER BY groupId, artifactId",
                    Map.of("pid", active.projectId())
            );
            if (deps.isEmpty()) {
                System.out.println("no Maven dependencies in graph; nothing to audit");
                return 0;
            }

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            ObjectMapper mapper = new ObjectMapper();

            List<Map<String, Object>> findings = new ArrayList<>();
            int errorCount = 0;

            for (Map<String, Object> d : deps) {
                String groupId = String.valueOf(d.get("groupId"));
                String artifactId = String.valueOf(d.get("artifactId"));
                String version = String.valueOf(d.get("version"));
                if (version == null || version.isBlank() || "null".equals(version)) continue;
                String coord = groupId + ":" + artifactId + ":" + version;
                try {
                    String body = String.format(
                            "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"%s:%s\"},\"version\":\"%s\"}",
                            groupId, artifactId, version
                    );
                    HttpResponse<String> resp = http.send(
                            HttpRequest.newBuilder(URI.create(OSV_URL))
                                    .timeout(Duration.ofSeconds(timeoutSeconds))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    if (resp.statusCode() != 200) {
                        errorCount++;
                        continue;
                    }
                    JsonNode root = mapper.readTree(resp.body());
                    JsonNode vulns = root.path("vulns");
                    if (!vulns.isArray() || vulns.isEmpty()) continue;
                    for (JsonNode v : vulns) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("dependency", coord);
                        row.put("id", v.path("id").asText());
                        row.put("summary", v.path("summary").asText(""));
                        row.put("severity", primarySeverity(v));
                        findings.add(row);
                    }
                } catch (Exception e) {
                    errorCount++;
                }
            }

            System.out.println("audit (active project: " + active.name() + ")");
            System.out.println("  dependencies scanned: " + deps.size());
            if (errorCount > 0) System.out.println("  network/parse errors:  " + errorCount);
            System.out.println("  vulnerabilities found: " + findings.size());
            System.out.println();
            if (!findings.isEmpty()) TableRenderer.render(System.out, findings);

            if (ci && !findings.isEmpty()) return 1;
            return 0;
        }
    }

    private static String primarySeverity(JsonNode vuln) {
        JsonNode sev = vuln.path("severity");
        if (sev.isArray() && !sev.isEmpty()) {
            return sev.get(0).path("type").asText("") + ":" + sev.get(0).path("score").asText("");
        }
        JsonNode db = vuln.path("database_specific");
        if (!db.isMissingNode()) {
            String s = db.path("severity").asText("");
            if (!s.isEmpty()) return s;
        }
        return "unknown";
    }
}
