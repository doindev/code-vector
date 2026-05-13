package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema rollup for the dashboard's Explorer view. Returns one row per node label and one
 * row per edge type, each with a count, sorted by count descending so the heaviest tables
 * surface first. Cheap to compute -- {@link GraphStore#nodeCounts} and
 * {@link GraphStore#edgeCounts} are already cached by the backend driver.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/graph")
public class SchemaController {

    private final GraphStore store;
    private final ActiveProject project;
    private final GraphReadCache cache;

    public SchemaController(GraphStore restGraphStore, ActiveProject activeProject, GraphReadCache cache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.cache = cache;
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() {
        return cache.memoize("graph-schema:" + project.projectId(), this::buildSchema);
    }

    private Map<String, Object> buildSchema() {
        String pid = project.projectId();
        Map<String, Long> rawNodes = store.nodeCounts(pid);
        Map<String, Long> rawEdges = store.edgeCounts(pid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("projectId", pid);
        out.put("labels", sortByCount(rawNodes));
        out.put("relTypes", sortByCount(rawEdges));
        out.put("totals", Map.of(
                "labels", rawNodes.size(),
                "relTypes", rawEdges.size(),
                "nodes", rawNodes.values().stream().mapToLong(Long::longValue).sum(),
                "edges", rawEdges.values().stream().mapToLong(Long::longValue).sum()
        ));
        // Meta-graph: which labels connect to which via which edge types, with counts.
        // The dashboard's Explorer view renders this as a Cytoscape graph in the Schema tab.
        out.put("connectivity", store.schemaConnectivity(pid));
        return out;
    }

    /**
     * Convert {@code Map<name, count>} into the order the UI expects: {@code [{name, count}, ...]}
     * sorted by count descending, then name ascending. Stable sort so re-renders don't shuffle rows.
     */
    private static List<Map<String, Object>> sortByCount(Map<String, Long> raw) {
        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("count", e.getValue());
            rows.add(row);
        }
        rows.sort(Comparator
                .comparingLong((Map<String, Object> r) -> -((Number) r.get("count")).longValue())
                .thenComparing(r -> (String) r.get("name")));
        return rows;
    }
}
