package io.doindev.cvector.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import io.doindev.cvector.rules.RulesConfig;
import io.doindev.cvector.rules.RulesConfigLoader;
import io.doindev.cvector.rules.RulesEngine;
import io.doindev.cvector.rules.Severity;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "badge", description = "Generate README health badges (shields.io) from the graph.", mixinStandardHelpOptions = true)
public class BadgeCommand implements Callable<Integer> {

    private static final String MARKER_START = "<!-- cvector:badges-start -->";
    private static final String MARKER_END = "<!-- cvector:badges-end -->";
    private static final String OSV_URL = "https://api.osv.dev/v1/query";

    @Option(names = "--out", description = "Write the markdown block to this file instead of stdout.")
    private Path out;

    @Option(names = "--inject", description = "Inject/update badges between cvector:badges markers in this file.")
    private Path inject;

    @Option(names = "--skip-audit", description = "Skip the vulnerability badge (avoids OSV network call).")
    private boolean skipAudit;

    @Option(names = "--shields-base", description = "Override the shields.io base URL.")
    private String shieldsBase = "https://img.shields.io/badge";

    private final CvectorRuntime runtime;

    public BadgeCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            String pid = active.projectId();
            Map<String, Long> nodes = store.nodeCounts(pid);
            List<Badge> badges = new ArrayList<>();
            badges.add(rulesBadge(store, active, pid));

            long deps = nodes.getOrDefault("MavenDependency", 0L);
            badges.add(new Badge("dependencies", String.valueOf(deps), deps == 0 ? "lightgrey" : "blue"));

            long methods = nodes.getOrDefault("Method", 0L);
            badges.add(new Badge("methods", String.valueOf(methods), "blue"));

            long endpoints = nodes.getOrDefault("ApiEndpoint", 0L);
            badges.add(new Badge("endpoints", String.valueOf(endpoints), endpoints == 0 ? "lightgrey" : "blue"));

            long tables = nodes.getOrDefault("Table", 0L);
            if (tables > 0) badges.add(new Badge("tables", String.valueOf(tables), "blue"));

            if (!skipAudit) {
                int vulns = countVulnerabilities(store, pid);
                String color = vulns == 0 ? "brightgreen" : vulns <= 5 ? "orange" : "red";
                badges.add(new Badge("vulnerabilities", String.valueOf(vulns), color));
            }

            String md = renderMarkdown(badges, active.name());

            if (inject != null) {
                int written = injectMarkers(inject, md);
                System.out.println("injected " + badges.size() + " badge(s) into " + inject
                        + " (" + written + " bytes)");
            } else if (out != null) {
                Files.writeString(out, md);
                System.out.println("wrote " + badges.size() + " badge(s) to " + out);
            } else {
                System.out.println(md);
            }
        }
        return 0;
    }

    private Badge rulesBadge(GraphStore store, CvectorConfig.ProjectEntry active, String pid) {
        Path rulesYml = Path.of(active.rootPath()).resolve(".cvector").resolve("rules.yml");
        RulesConfig rulesCfg = RulesConfigLoader.loadOrDefault(rulesYml);
        RulesEngine.Report report = new RulesEngine(pid, store, rulesCfg).run();
        if (report.hasErrors()) return new Badge("cvector rules", "failing", "red");
        int warn = report.bySeverity().getOrDefault(Severity.WARN.name(), 0);
        if (warn > 0) return new Badge("cvector rules", "warnings", "yellow");
        return new Badge("cvector rules", "passing", "brightgreen");
    }

    private int countVulnerabilities(GraphStore store, String pid) {
        List<Map<String, Object>> deps = store.mavenDependencies(pid);
        if (deps.isEmpty()) return 0;

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ObjectMapper mapper = new ObjectMapper();
        int vulns = 0;
        for (Map<String, Object> d : deps) {
            String groupId = stringOf(d.get("groupId"));
            String artifactId = stringOf(d.get("artifactId"));
            String version = stringOf(d.get("version"));
            if (version == null || version.isBlank() || "null".equals(version) || version.contains("${")) continue;
            try {
                String body = String.format(
                        "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"%s:%s\"},\"version\":\"%s\"}",
                        groupId, artifactId, version);
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(OSV_URL))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;
                JsonNode root = mapper.readTree(resp.body());
                JsonNode v = root.path("vulns");
                if (v.isArray()) vulns += v.size();
            } catch (Exception ignored) {
            }
        }
        return vulns;
    }

    private static String stringOf(Object v) { return v == null ? null : v.toString(); }

    private String renderMarkdown(List<Badge> badges, String projectName) {
        StringBuilder s = new StringBuilder();
        s.append("<!-- cvector badges for ").append(projectName).append(" — regenerate with `cvector badge` -->\n");
        for (Badge b : badges) {
            String label = encodeBadgePart(b.label());
            String value = encodeBadgePart(b.value());
            String url = shieldsBase + "/" + label + "-" + value + "-" + b.color();
            s.append("![").append(b.label()).append("](").append(url).append(") ");
        }
        s.append("\n");
        return s.toString();
    }

    private static String encodeBadgePart(String s) {
        return s.replace("-", "--").replace("_", "__").replace(" ", "%20");
    }

    private static int injectMarkers(Path file, String block) throws java.io.IOException {
        String existing = Files.exists(file) ? Files.readString(file) : "";
        String wrapped = MARKER_START + "\n" + block + MARKER_END;

        int start = existing.indexOf(MARKER_START);
        int end = existing.indexOf(MARKER_END);
        String updated;
        if (start >= 0 && end > start) {
            updated = existing.substring(0, start) + wrapped + existing.substring(end + MARKER_END.length());
        } else {
            String sep = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
            updated = existing + sep + wrapped + "\n";
        }
        Files.writeString(file, updated);
        return updated.length();
    }

    private record Badge(String label, String value, String color) {}
}
