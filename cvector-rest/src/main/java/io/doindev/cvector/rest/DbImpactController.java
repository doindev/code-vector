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
 * Table/column blast-radius: returns the methods that {@code READS_TABLE} / {@code WRITES_TABLE}
 * (and, optionally, {@code READS_COLUMN} / {@code WRITES_COLUMN}) edges point at, so a developer
 * about to alter a schema can see who downstream needs to be updated.
 *
 * <p>{@code GET /api/db-impact?table=users&column=email}. Returns
 * {@code {readers, writers, project, table, column}}; each list has at most 500 rows.
 * Cached through {@link GraphReadCache} with the table/column folded into the key.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class DbImpactController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public DbImpactController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/db-impact", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] dbImpact(@RequestParam("table") String table,
                           @RequestParam(value = "column", required = false) String column) {
        String pid = project.projectId();
        String colKey = column == null ? "" : column;
        String key = "db-impact:" + pid + ":" + table + ":" + colKey;
        return jsonCache.memoize(key, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", Map.of("projectId", pid, "name", project.name()));
            out.put("table", table);
            out.put("column", column);
            Map<String, List<Map<String, Object>>> impact = store.dbImpact(pid, table, column);
            List<Map<String, Object>> readers = impact.getOrDefault("readers", List.of());
            List<Map<String, Object>> writers = impact.getOrDefault("writers", List.of());
            out.put("readers", readers);
            out.put("writers", writers);
            out.put("readerCount", readers.size());
            out.put("writerCount", writers.size());
            return out;
        });
    }
}
