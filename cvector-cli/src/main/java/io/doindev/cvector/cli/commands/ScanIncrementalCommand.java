package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.util.GitHelper;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.core.store.GraphStore;
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

        try (GraphStore store = runtime.openGraphStore(cfg);
             GraphIngestor ingestor = store.openIngestor()) {
            store.bootstrapSchema();

            String baseCommit = fromCommit;
            if (baseCommit == null) {
                Map<String, Object> meta = store.projectMeta(ctx.projectId());
                Object sha = meta.get("lastScanCommit");
                if (sha != null) baseCommit = sha.toString();
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
            System.out.println("backend: " + store.displayUri());
            System.out.println("base: " + baseCommit);
            System.out.println("head: " + currentHead.get());
            System.out.println("changed: " + changed.size() + ", deleted: " + deleted.size());

            int removed = 0;
            for (Path deletedPath : deleted) {
                String rel = scanRoot.relativize(deletedPath).toString().replace('\\', '/');
                removed += store.deleteFileSubtree(ctx.projectId(), rel);
            }
            if (removed > 0) System.out.printf("removed %d node(s) for deleted files%n", removed);

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
