package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
@Command(name = "scan", description = "Scan a path and ingest into Neo4j.")
public class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Directory to scan (default: current directory).")
    private Path path = Paths.get(".");

    @Option(names = "--project", description = "Project name to scan into (defaults to active project).")
    private String project;

    @Option(names = "--no-clean", description = "Skip removing nodes that became stale (default: clean).")
    private boolean noClean;

    private final CvectorRuntime runtime;
    private final List<Parser> parsers;

    public ScanCommand(CvectorRuntime runtime, List<Parser> parsers) {
        this.runtime = runtime;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        if (project != null) {
            if (!cfg.projects().containsKey(project)) {
                System.err.println("project '" + project + "' not found in config");
                return 1;
            }
            cfg = new CvectorConfig(project, cfg.projects(), cfg.neo4j());
        }
        ProjectContext ctx = runtime.projectContext(cfg);
        Path scanRoot = path.toAbsolutePath().normalize();

        System.out.println("scanning " + scanRoot + " into project '" + ctx.projectName() + "' (" + ctx.projectId() + ")");
        System.out.println("parsers: " + parsers.stream().map(Parser::name).toList());

        ZonedDateTime scanStart = ZonedDateTime.now(ZoneOffset.UTC);
        try (Neo4jClient client = runtime.openNeo4j(cfg);
             Ingestor ingestor = new Ingestor(client)) {
            new SchemaBootstrap(client).bootstrap();

            Optional<String> head = GitHelper.head(scanRoot);
            emitProjectNode(ctx, head.orElse(null), ingestor);

            for (Parser p : parsers) p.prepare(ctx);

            // Pre-compute extension → parser map so dispatch is O(1) per file instead of O(N parsers).
            // Parsers with custom accepts() (Dockerfile, TypeScript skip list) fall back to the linear scan.
            Map<String, Parser> byExt = new HashMap<>();
            List<Parser> customAccepts = new ArrayList<>();
            for (Parser p : parsers) {
                if (hasCustomAccepts(p)) {
                    customAccepts.add(p);
                } else {
                    for (String ext : p.supportedExtensions()) byExt.putIfAbsent(ext.toLowerCase(), p);
                }
            }

            AtomicInteger fileCount = new AtomicInteger();
            long startMs = System.currentTimeMillis();
            try (Stream<Path> walk = Files.walk(scanRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(ScanCommand::notInIgnored)
                        .forEach(file -> {
                            Parser p = dispatch(file, byExt, customAccepts);
                            if (p != null) {
                                p.parse(file, ctx, ingestor);
                                fileCount.incrementAndGet();
                            }
                        });
            }
            for (Parser p : parsers) p.finish();
            ingestor.flush();

            long elapsedMs = System.currentTimeMillis() - startMs;
            System.out.printf("scanned %d file(s) in %dms%n", fileCount.get(), elapsedMs);
            System.out.printf("nodes upserted: %d, edges upserted: %d%n", ingestor.totalNodes(), ingestor.totalEdges());

            int linked = resolveDeferredHandlers(client, ctx.projectId());
            if (linked > 0) System.out.printf("linked %d deferred handler(s)%n", linked);

            int rewired = resolveUnresolvedCalls(client, ctx.projectId());
            if (rewired > 0) System.out.printf("resolved %d cross-file call(s)%n", rewired);

            if (!noClean) {
                int removed = cleanupStale(client, ctx.projectId(), scanStart);
                if (removed > 0) System.out.printf("removed %d stale node(s) from prior scans%n", removed);
            }
        }
        return 0;
    }

    /**
     * Resolve cross-file handler references that the per-file parsers couldn't link directly.
     * Currently: Django ApiEndpoints carrying a {@code viewRef} like {@code views.user_detail}
     * get a {@code HANDLES} edge to any Method whose name matches the last dotted segment.
     */
    private static int resolveDeferredHandlers(Neo4jClient client, String projectId) {
        var rows = client.read(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "WHERE e.viewRef IS NOT NULL AND NOT EXISTS { MATCH (e)-[:HANDLES]->() } "
                        + "WITH e, split(e.viewRef, '.') AS parts "
                        + "WITH e, parts[size(parts)-1] AS handlerName "
                        + "MATCH (m:Method {projectId: $pid, name: handlerName}) "
                        + "RETURN count(m) AS pending",
                Map.of("pid", projectId)
        );
        long pending = rows.isEmpty() ? 0 : rows.get(0).get("pending").asLong();
        if (pending == 0) return 0;
        client.write(
                "MATCH (e:ApiEndpoint {projectId: $pid}) "
                        + "WHERE e.viewRef IS NOT NULL AND NOT EXISTS { MATCH (e)-[:HANDLES]->() } "
                        + "WITH e, split(e.viewRef, '.') AS parts "
                        + "WITH e, parts[size(parts)-1] AS handlerName "
                        + "MATCH (m:Method {projectId: $pid, name: handlerName}) "
                        + "MERGE (e)-[:HANDLES]->(m)",
                Map.of("pid", projectId)
        );
        return (int) pending;
    }

    /**
     * Post-scan resolution: parsers emit cross-file calls as `unresolved.<name>:<arity>` placeholder
     * Method nodes. This pass rewires CALLS edges that target such placeholders to the real project
     * Method node(s) with matching simple name + parameter count, then deletes placeholders whose
     * incoming edges were all rewired. Replaces JavaSymbolSolver-based per-call resolution which was
     * by far the hottest path in the previous parse loop.
     */
    private static int resolveUnresolvedCalls(Neo4jClient client, String projectId) {
        // Phase 1: rewire placeholders with exactly one project-method candidate (confident match).
        client.write(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND placeholder.fqName CONTAINS ':' "
                        + "WITH placeholder, "
                        + "     split(placeholder.fqName, ':')[size(split(placeholder.fqName, ':'))-1] AS arityStr "
                        + "WITH placeholder, placeholder.name AS name, toInteger(arityStr) AS arity "
                        + "MATCH (cand:Method {projectId: $pid, name: name, paramCount: arity}) "
                        + "WHERE NOT cand.fqName STARTS WITH 'unresolved.' "
                        + "WITH placeholder, collect(cand) AS candidates "
                        + "WHERE size(candidates) = 1 "
                        + "WITH placeholder, candidates[0] AS resolved "
                        + "MATCH (caller)-[old:CALLS]->(placeholder) "
                        + "WITH caller, old, placeholder, resolved, properties(old) AS oldProps "
                        + "MERGE (caller)-[new:CALLS]->(resolved) "
                        + "SET new += oldProps, new.confidence = coalesce(oldProps.confidence, 0.5) + 0.2 "
                        + "DELETE old",
                Map.of("pid", projectId)
        );

        // Phase 2: count placeholders that were fully rewired (now orphaned) for the user-facing report.
        var count = client.read(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "RETURN count(placeholder) AS c",
                Map.of("pid", projectId)
        );
        long rewired = count.isEmpty() ? 0 : count.get(0).get("c").asLong();

        // Phase 3: drop orphaned placeholders.
        client.write(
                "MATCH (placeholder:Method {projectId: $pid}) "
                        + "WHERE placeholder.fqName STARTS WITH 'unresolved.' "
                        + "AND NOT EXISTS { MATCH ()-[:CALLS]->(placeholder) } "
                        + "DETACH DELETE placeholder",
                Map.of("pid", projectId)
        );
        return (int) rewired;
    }

    /** Single-pass stale node cleanup. Replaces 13 per-label read+write round-trips with one count + one delete. */
    private static int cleanupStale(Neo4jClient client, String projectId, ZonedDateTime cutoff) {
        List<String> labels = List.of("Method", "Class", "Field", "File", "ApiEndpoint",
                "ConfigKey", "EnvVar", "MavenDependency", "Table", "Column",
                "CssClass", "DesignToken", "CssMixin");
        String labelPredicate = labels.stream()
                .map(l -> "n:" + l)
                .reduce((a, b) -> a + " OR " + b)
                .orElse("false");
        var rows = client.read(
                "MATCH (n) "
                        + "WHERE n.projectId = $pid "
                        + "AND (" + labelPredicate + ") "
                        + "AND n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt < datetime($cutoff) "
                        + "RETURN count(n) AS c",
                Map.of("pid", projectId, "cutoff", cutoff.toString())
        );
        long count = rows.isEmpty() ? 0L : rows.get(0).get("c").asLong();
        if (count == 0) return 0;
        client.write(
                "MATCH (n) "
                        + "WHERE n.projectId = $pid "
                        + "AND (" + labelPredicate + ") "
                        + "AND n.lastIngestedAt IS NOT NULL AND n.lastIngestedAt < datetime($cutoff) "
                        + "DETACH DELETE n",
                Map.of("pid", projectId, "cutoff", cutoff.toString())
        );
        return (int) count;
    }

    private static boolean hasCustomAccepts(Parser p) {
        try {
            return p.getClass().getDeclaredMethod("accepts", Path.class) != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Parser dispatch(Path file, Map<String, Parser> byExt, List<Parser> customAccepts) {
        for (Parser p : customAccepts) {
            if (p.accepts(file)) return p;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return byExt.get(name.substring(dot + 1).toLowerCase());
    }

    static void emitProjectNode(ProjectContext ctx, String head, Ingestor ingestor) {
        NodeKey projectKey = new NodeKey(ctx.projectId(), "Project", ctx.projectId());
        Map<String, Object> props = new HashMap<>();
        props.put("name", ctx.projectName());
        props.put("fqName", ctx.projectId());
        props.put("rootPath", ctx.rootPath().toString());
        if (head != null) props.put("lastScanCommit", head);
        ingestor.accept(new GraphEvent.NodeUpsert(projectKey, props));
    }

    private static boolean notInIgnored(Path p) {
        for (Path part : p) {
            String name = part.getFileName().toString();
            if (name.equals("target") || name.equals("build") || name.equals("node_modules")
                    || name.equals(".git") || name.equals(".cvector")) {
                return false;
            }
        }
        return true;
    }
}
