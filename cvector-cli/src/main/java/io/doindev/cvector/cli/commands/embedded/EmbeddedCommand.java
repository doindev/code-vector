package io.doindev.cvector.cli.commands.embedded;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.embedded.EmbeddedKuzu;
import io.doindev.cvector.embedded.KuzuSchemaBootstrap;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Admin commands for the embedded KuzuDB store. KuzuDB runs in-process so there's no server to
 * start or stop — these commands manage the on-disk database directory and let you exercise it
 * directly.
 */
@Component
@Command(
        name = "embedded",
        description = "Manage the embedded KuzuDB store for the active project.",
        mixinStandardHelpOptions = true,
        subcommands = {
                EmbeddedCommand.Init.class,
                EmbeddedCommand.Info.class,
                EmbeddedCommand.Query.class,
                EmbeddedCommand.Wipe.class,
                HelpCommand.class
        }
)
public class EmbeddedCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }

    private static String activeProjectId(CvectorRuntime runtime) {
        CvectorConfig cfg = runtime.loadConfig();
        return runtime.requireActiveProject(cfg).projectId();
    }

    /** Resolves the active project's Kuzu DB path honoring the shared/isolated layout. */
    private static Path activeDbPath(CvectorRuntime runtime) {
        CvectorConfig cfg = runtime.loadConfig();
        String pid = runtime.requireActiveProject(cfg).projectId();
        return EmbeddedKuzu.defaultDbPath(cfg, pid);
    }

    @Component
    @Command(name = "init", description = "Create the embedded database directory and bootstrap its schema.", mixinStandardHelpOptions = true)
    public static class Init implements Callable<Integer> {
        private final CvectorRuntime runtime;
        public Init(CvectorRuntime runtime) { this.runtime = runtime; }
        @Override
        public Integer call() throws IOException {
            CvectorConfig cfg = runtime.loadConfig();
            String pid = runtime.requireActiveProject(cfg).projectId();
            Path db = EmbeddedKuzu.defaultDbPath(cfg, pid);
            try (EmbeddedKuzu k = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                new KuzuSchemaBootstrap(k).bootstrap();
            }
            System.out.println("initialized: " + db + (cfg.isSharedDbMode(pid) ? " (shared)" : " (isolated)"));
            return 0;
        }
    }

    @Component
    @Command(name = "info", description = "Show the embedded database path, size, and table inventory.", mixinStandardHelpOptions = true)
    public static class Info implements Callable<Integer> {
        private final CvectorRuntime runtime;
        public Info(CvectorRuntime runtime) { this.runtime = runtime; }
        @Override
        public Integer call() throws IOException {
            CvectorConfig cfg = runtime.loadConfig();
            String pid = runtime.requireActiveProject(cfg).projectId();
            Path db = EmbeddedKuzu.defaultDbPath(cfg, pid);
            boolean shared = cfg.isSharedDbMode(pid);
            if (!Files.exists(db)) {
                System.out.println("not initialized (no DB at " + db + ")");
                return 0;
            }
            long bytes = directorySize(db.getParent());
            System.out.printf("path:   %s%n", db);
            System.out.printf("layout: %s%n", shared ? "shared (one DB serves every non-isolated project)" : "isolated (per-project directory)");
            System.out.printf("size:   %.2f MB%n", bytes / (1024.0 * 1024.0));
            try (EmbeddedKuzu k = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                List<Map<String, Object>> tables = k.read("CALL SHOW_TABLES() RETURN *");
                System.out.printf("tables: %d%n", tables.size());
                for (Map<String, Object> t : tables) {
                    System.out.printf("  %-10s %s%n", t.get("type"), t.get("name"));
                }
                if (shared) {
                    // Show per-project node counts so the operator can see what's in the shared DB.
                    List<Map<String, Object>> projectCounts = k.read(
                            "MATCH (n:Node) RETURN n.projectId AS projectId, count(n) AS nodes "
                                    + "ORDER BY nodes DESC LIMIT 20");
                    if (!projectCounts.isEmpty()) {
                        System.out.println("projects in this shared DB:");
                        for (Map<String, Object> row : projectCounts) {
                            System.out.printf("  %-40s %d nodes%n", row.get("projectId"), ((Number) row.get("nodes")).longValue());
                        }
                    }
                }
            }
            return 0;
        }

        private static long directorySize(Path dir) throws IOException {
            try (Stream<Path> walk = Files.walk(dir)) {
                return walk.filter(Files::isRegularFile)
                        .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0L; } })
                        .sum();
            }
        }
    }

    @Component
    @Command(name = "query", description = "Run an ad-hoc Cypher query against the embedded database.", mixinStandardHelpOptions = true)
    public static class Query implements Callable<Integer> {
        @Parameters(index = "0", description = "Cypher query to execute.")
        String cypher;

        private final CvectorRuntime runtime;
        public Query(CvectorRuntime runtime) { this.runtime = runtime; }

        @Override
        public Integer call() throws IOException {
            CvectorConfig cfg = runtime.loadConfig();
            String pid = runtime.requireActiveProject(cfg).projectId();
            Path db = EmbeddedKuzu.defaultDbPath(cfg, pid);
            try (EmbeddedKuzu k = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                List<Map<String, Object>> rows = k.read(cypher);
                if (rows.isEmpty()) {
                    System.out.println("(no rows)");
                    return 0;
                }
                for (String col : rows.get(0).keySet()) System.out.print(col + "\t");
                System.out.println();
                for (Map<String, Object> r : rows) {
                    for (Object v : r.values()) System.out.print(v + "\t");
                    System.out.println();
                }
            }
            return 0;
        }
    }

    @Component
    @Command(name = "wipe", description = "Delete the active project's graph data. On a shared DB only this project's rows go; isolated projects get their directory removed.", mixinStandardHelpOptions = true)
    public static class Wipe implements Callable<Integer> {
        private final CvectorRuntime runtime;
        public Wipe(CvectorRuntime runtime) { this.runtime = runtime; }
        @Override
        public Integer call() throws IOException {
            CvectorConfig cfg = runtime.loadConfig();
            String pid = runtime.requireActiveProject(cfg).projectId();
            Path db = EmbeddedKuzu.defaultDbPath(cfg, pid);
            if (!Files.exists(db)) {
                System.out.println("nothing to wipe (no DB at " + db + ")");
                return 0;
            }
            if (cfg.isSharedDbMode(pid)) {
                // Shared DB: surgically DETACH DELETE only this project's rows so other
                // projects in the same DB keep working. Slower than rm -rf but correct.
                try (EmbeddedKuzu k = new EmbeddedKuzu(db, EmbeddedKuzu.bufferSizeFromConfig(cfg))) {
                    k.write("MATCH (n:Node) WHERE n.projectId = $pid DETACH DELETE n", Map.of("pid", pid));
                }
                System.out.println("wiped project " + pid + " from shared DB at " + db);
                return 0;
            }
            // Isolated layout: delete the per-project directory.
            Path dir = db.getParent();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) { /* best effort */ }
                });
            }
            System.out.println("wiped (isolated): " + dir);
            return 0;
        }
    }
}
