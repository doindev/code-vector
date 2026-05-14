package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rename blast-radius surface: callers, references, importers, plus the symbol's own
 * definition. Mirrors {@code cv_rename} (MCP) and the existing {@code RenameCommand} (CLI) so
 * all three surfaces return the same shape.
 *
 * <p>{@code GET /api/rename?symbol=<fqName>}. Cached through {@link GraphReadCache}.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class RenameController {

    private static final int REFERENCE_CAP = 500;
    private static final int IMPORTER_CAP = 200;

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public RenameController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/rename", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] rename(@RequestParam("symbol") String symbol) {
        String key = "rename:" + project.projectId() + ":" + symbol;
        return jsonCache.memoize(key, () -> compute(symbol));
    }

    private Map<String, Object> compute(String symbol) {
        String pid = project.projectId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("projectId", pid, "name", project.name()));
        out.put("query", symbol);
        List<Map<String, Object>> hits = store.findSymbol(pid, symbol);
        if (hits.isEmpty()) {
            out.put("found", false);
            return out;
        }
        Map<String, Object> hit = hits.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        if (hit.get("fileId") != null) {
            out.put("file", store.fileOf(pid, (String) hit.get("fileId")));
        }
        List<Map<String, Object>> callers = store.callers(pid, id);
        List<Map<String, Object>> references = store.referencingNodes(pid, id, REFERENCE_CAP);
        List<Map<String, Object>> imports = store.importingFiles(pid, id, IMPORTER_CAP);
        out.put("callers", callers);
        out.put("references", references);
        out.put("importingFiles", imports);
        out.put("totals", Map.of(
                "callers", callers.size(),
                "references", references.size(),
                "importers", imports.size()
        ));
        return out;
    }
}
