package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Ingestor;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.SchemaBootstrap;
import io.doindev.cvector.watcher.CvectorWatcher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
@Command(name = "watch", description = "Live file watcher (default) or scheduled re-scan via --cron.")
public class WatchCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Directory to watch (default: current directory).")
    private Path path = Paths.get(".");

    @Option(names = "--debounce", description = "Debounce window in milliseconds (live mode, default 250).")
    private long debounceMillis = 250L;

    @Option(names = "--cron", description = "Cron expression for periodic full re-scan (e.g. '0 */5 * * * *'). Overrides live mode.")
    private String cronExpr;

    private final CvectorRuntime runtime;
    private final List<Parser> parsers;

    public WatchCommand(CvectorRuntime runtime, List<Parser> parsers) {
        this.runtime = runtime;
        this.parsers = parsers;
    }

    @Override
    public Integer call() throws Exception {
        CvectorConfig cfg = runtime.loadConfig();
        ProjectContext ctx = runtime.projectContext(cfg);
        Path watchRoot = path.toAbsolutePath().normalize();

        if (cronExpr != null && !cronExpr.isBlank()) {
            return runCron(cfg, ctx, watchRoot);
        }
        return runLive(cfg, ctx, watchRoot);
    }

    private Integer runLive(CvectorConfig cfg, ProjectContext ctx, Path watchRoot) throws Exception {
        try (Neo4jClient client = runtime.openNeo4j(cfg);
             CvectorWatcher watcher = new CvectorWatcher(ctx, parsers, client, debounceMillis)) {
            new SchemaBootstrap(client).bootstrap();
            watcher.start();
            System.out.println("watching " + watchRoot + " (live mode, debounce " + debounceMillis + "ms)");
            System.out.println("press Ctrl-C to stop");

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println();
                System.out.println("stopping watcher...");
                shutdown.countDown();
            }, "cvector-watch-shutdown"));
            shutdown.await();
            System.out.printf("processed %d update(s), %d delete(s), %d transient failure(s)%n",
                    watcher.totalFilesProcessed(), watcher.totalDeletes(), watcher.transientFailures());
        }
        return 0;
    }

    private Integer runCron(CvectorConfig cfg, ProjectContext ctx, Path watchRoot) throws Exception {
        CronExpression cron;
        try {
            cron = CronExpression.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            System.err.println("invalid cron expression: " + e.getMessage());
            return 2;
        }
        System.out.println("watching " + watchRoot + " on cron schedule '" + cronExpr + "'");
        System.out.println("press Ctrl-C to stop");

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("stopping cron watcher...");
            shutdown.countDown();
        }, "cvector-cron-shutdown"));

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            new SchemaBootstrap(client).bootstrap();
            for (Parser p : parsers) p.prepare(ctx);

            int iterations = 0;
            int totalFiles = 0;
            while (shutdown.getCount() > 0) {
                ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
                ZonedDateTime next = cron.next(now);
                if (next == null) {
                    System.err.println("cron expression yields no further firings; exiting");
                    break;
                }
                long delayMs = Math.max(0, Duration.between(now, next).toMillis());
                System.out.printf("next re-scan at %s (in %ds)%n", next, delayMs / 1000);
                if (shutdown.await(delayMs, TimeUnit.MILLISECONDS)) break;

                long startMs = System.currentTimeMillis();
                AtomicInteger files = new AtomicInteger();
                try (Ingestor ingestor = new Ingestor(client)) {
                    try (Stream<Path> walk = Files.walk(watchRoot)) {
                        walk.filter(Files::isRegularFile)
                                .filter(WatchCommand::notInIgnored)
                                .forEach(file -> {
                                    for (Parser p : parsers) {
                                        if (!p.accepts(file)) continue;
                                        try {
                                            p.parse(file, ctx, ingestor);
                                            files.incrementAndGet();
                                        } catch (RuntimeException ex) {
                                            System.err.printf("  skip %s: %s%n",
                                                    watchRoot.relativize(file), ex.getMessage());
                                        }
                                        break;
                                    }
                                });
                    }
                    ingestor.flush();
                    iterations++;
                    totalFiles += files.get();
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    System.out.printf("[iter %d] re-scanned %d files in %dms (nodes=%d edges=%d)%n",
                            iterations, files.get(), elapsedMs, ingestor.totalNodes(), ingestor.totalEdges());
                }
            }
            for (Parser p : parsers) p.finish();
            System.out.printf("cron mode stopped after %d iteration(s), %d file scans%n", iterations, totalFiles);
        }
        return 0;
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
