package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code cvector test-impact <symbol> [--depth N]} — list the test methods whose call chain
 * reaches the given symbol within {@code depth} hops. Mirrors {@code /api/test-impact} and
 * {@code cv_test_impact}; uses {@link GraphStore#testReach}.
 *
 * <p>The depth default of 5 matches the MCP tool so the CLI / REST / MCP surfaces all return
 * the same shape for the same input.
 */
@Component
@Command(name = "test-impact",
        description = "Find tests that transitively reach the given symbol (via CALLS/REFERENCES).",
        mixinStandardHelpOptions = true)
public class TestImpactCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Symbol (fully qualified or last segment).")
    private String symbol;

    @Option(names = "--depth", description = "Max traversal depth (default 5).")
    private int depth = 5;

    private final CvectorRuntime runtime;

    public TestImpactCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            List<Map<String, Object>> hits = store.findSymbol(active.projectId(), symbol);
            if (hits.isEmpty()) {
                System.err.println("no symbol found matching '" + symbol + "'");
                return 1;
            }
            Map<String, Object> hit = hits.get(0);
            String id = (String) hit.get("id");
            String fqName = (String) hit.get("fqName");
            List<Map<String, Object>> tests = store.testReach(active.projectId(), id, depth);

            System.out.println("tests reaching: " + fqName + " (depth=" + depth + ")");
            if (tests.isEmpty()) {
                System.out.println("  (no tests transitively reach this symbol)");
                return 0;
            }
            TableRenderer.render(System.out, tests);
            System.out.println();
            System.out.println("total tests: " + tests.size());
            return 0;
        }
    }
}
