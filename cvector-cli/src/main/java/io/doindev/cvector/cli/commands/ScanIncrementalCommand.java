package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Component
@Command(name = "scan:incremental", description = "Re-parse only files changed since the last scan (git-diff-driven).")
public class ScanIncrementalCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Project root (default: current directory).")
    private Path path = Paths.get(".");

    @Option(names = "--from", description = "Override the base commit (defaults to Project.lastScanCommit).")
    private String fromCommit;

    private final CvectorRuntime runtime;
    private final List<Parser> parsers;

    public ScanIncrementalCommand(CvectorRuntime runtime, List<Parser> parsers) {
        this.runtime = runtime;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        ProjectContext ctx = runtime.projectContext(cfg);
        Path scanRoot = path.toAbsolutePath().normalize();

        if (!GitHelper.isGitRepo(scanRoot)) {
            System.err.println("not a git repository: " + scanRoot);
            return 2;
        }

        Optional<String> currentHead = GitHelper.head(scanRoot);
        if (currentHead.isEmpty()) {
            System.err.println("could not determine git HEAD");
            return 2;
        }

        try (Neo4jClient client = runtime.openNeo4j(cfg);
             Ingestor ingestor = new Ingestor(client)) {
            new SchemaBootstrap(client).bootstrap();

            String baseCommit = fromCommit;
            if (baseCommit == null) {
                var rows = client.read(
                        "MATCH (p:Project {projectId: $pid}) RETURN p.lastScanCommit AS sha",
                        Map.of("pid", ctx.projectId())
                );
                if (!rows.isEmpty()) {
                    var v = rows.get(0).get("sha");
                    if (!v.isNull()) baseCommit = v.asString();
                }
            }
            if (baseCommit == null) {
                System.err.println("no previous scan commit found; run `cvector scan` first");
                return 2;
            }
            if (baseCommit.equals(currentHead.get())) {
                System.out.println("no commits since last scan (HEAD = " + baseCommit + ")");
                return 0;
            }

            List<Path> changed = GitHelper.changedSince(scanRoot, baseCommit);
            List<Path> deleted = GitHelper.deletedSince(scanRoot, baseCommit);
            System.out.println("base: " + baseCommit);
            System.out.println("head: " + currentHead.get());
            System.out.println("changed: " + changed.size() + ", deleted: " + deleted.size());

            for (Path deletedPath : deleted) {
                String rel = scanRoot.relativize(deletedPath).toString().replace('\\', '/');
                client.write(
                        "MATCH (f:File {projectId: $pid, path: $path}) "
                                + "OPTIONAL MATCH (f)-[:CONTAINS*0..]->(child) "
                                + "DETACH DELETE f, child",
                        Map.of("pid", ctx.projectId(), "path", rel)
                );
            }

            for (Parser p : parsers) p.prepare(ctx);
            int fileCount = 0;
            for (Path file : changed) {
                if (!Files.isRegularFile(file)) continue;
                for (Parser p : parsers) {
                    if (p.accepts(file)) {
                        p.parse(file, ctx, ingestor);
                        fileCount++;
                        break;
                    }
                }
            }
            for (Parser p : parsers) p.finish();

            ScanCommand.emitProjectNode(ctx, currentHead.get(), ingestor);
            ingestor.flush();

            System.out.printf("re-scanned %d file(s); nodes upserted: %d, edges upserted: %d%n",
                    fileCount, ingestor.totalNodes(), ingestor.totalEdges());
        }
        return 0;
    }
}
