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

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class QueryController {

    private final GraphStore store;
    private final ActiveProject project;

    public QueryController(GraphStore restGraphStore, ActiveProject activeProject) {
        this.store = restGraphStore;
        this.project = activeProject;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q) {
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), q);
        return Map.of("query", q, "count", matches.size(), "results", matches);
    }

    @GetMapping("/explain")
    public Map<String, Object> explain(@RequestParam("symbol") String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        out.put("query", symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        if ("Method".equals(hit.get("label"))) {
            out.put("callers", store.callers(project.projectId(), id));
            out.put("callees", store.callees(project.projectId(), id));
        }
        if (hit.get("fileId") != null) {
            out.put("file", store.fileOf(project.projectId(), (String) hit.get("fileId")));
        }
        return out;
    }

    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam("symbol") String symbol,
                                      @RequestParam(value = "depth", defaultValue = "3") int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        String id = (String) hit.get("id");
        out.put("found", true);
        out.put("symbol", hit);
        out.put("depth", depth);
        out.put("impacted", store.impactDownstream(project.projectId(), id, depth));
        return out;
    }

    @GetMapping("/test-impact")
    public Map<String, Object> testImpact(@RequestParam("symbol") String symbol,
                                          @RequestParam(value = "depth", defaultValue = "5") int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matches = store.findSymbol(project.projectId(), symbol);
        if (matches.isEmpty()) {
            out.put("found", false);
            out.put("query", symbol);
            return out;
        }
        Map<String, Object> hit = matches.get(0);
        out.put("found", true);
        out.put("symbol", hit);
        out.put("tests", store.testReach(project.projectId(), (String) hit.get("id"), depth));
        return out;
    }
}
