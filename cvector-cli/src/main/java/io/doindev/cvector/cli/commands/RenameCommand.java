package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code cvector rename <symbol>} — preview the blast radius of renaming a symbol.
 *
 * <p>Doesn't actually edit any files — that's the user's job with their editor. What we
 * surface is the full set of places the symbol is referenced so the user can size the
 * change. Mirrors {@code cv_rename} (MCP) so an agent and a human get the same view.
 *
 * <p>The command never reads or writes the symbol's source — it's a pure graph query, which
 * means it works on any language the parsers cover, not just languages with refactor support.
 */
@Component
@Command(name = "rename",
        description = "Preview the blast radius of renaming a symbol (callers, references, importers).",
        mixinStandardHelpOptions = true)
public class RenameCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Symbol (fully qualified or last segment).")
    private String symbol;

    private final CvectorRuntime runtime;

    public RenameCommand(CvectorRuntime runtime) {
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
            String fqName = String.valueOf(hit.get("fqName"));
            System.out.println("symbol:    " + fqName);
            System.out.println("kind:      " + hit.getOrDefault("label", ""));
            if (hit.get("fileId") != null) {
                Map<String, Object> file = store.fileOf(active.projectId(), (String) hit.get("fileId"));
                System.out.println("file:      " + file.getOrDefault("path", "(unknown)")
                        + " line " + hit.getOrDefault("startLine", "?"));
            }
            section("callers");
            List<Map<String, Object>> callers = store.callers(active.projectId(), id);
            render(callers);

            section("references");
            List<Map<String, Object>> references = store.referencingNodes(active.projectId(), id, 500);
            render(references);

            section("importing files");
            List<Map<String, Object>> imports = store.importingFiles(active.projectId(), id, 200);
            render(imports);

            System.out.println();
            System.out.println("totals: callers=" + callers.size()
                    + " references=" + references.size()
                    + " importers=" + imports.size());
            System.out.println("note: cvector does NOT edit your source — use this as a checklist for the actual rename.");
            return 0;
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void render(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            System.out.println("  (none)");
        } else {
            TableRenderer.render(System.out, rows);
        }
    }
}
