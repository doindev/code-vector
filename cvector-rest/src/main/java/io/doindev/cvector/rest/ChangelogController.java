package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-generated changelog: groups recently-ingested nodes by label so the dashboard can
 * render a structured "what's changed since the last scan" panel without having to
 * re-aggregate the flat {@code /api/recent} stream on the client.
 *
 * <p>Backed by {@link GraphStore#recentlyChanged}; cached through {@link GraphReadCache}
 * so the same window+limit isn't re-queried on every poll. Cache key incorporates the
 * window and limit because both change the result shape.
 *
 * <p>{@code GET /api/changelog?since=7d&limit=5000}.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class ChangelogController {

    private static final int DEFAULT_LIMIT = 5000;
    private static final int MAX_LIMIT = 20_000;
    /** How many sample items to surface per label group. Keeps the JSON payload bounded. */
    private static final int ITEMS_PER_LABEL = 50;

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public ChangelogController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/changelog", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public byte[] changelog(
            @RequestParam(value = "since", defaultValue = "7d") String since,
            @RequestParam(value = "limit", defaultValue = "5000") int limit
    ) {
        int effectiveLimit = clamp(limit, 1, MAX_LIMIT);
        String window = sanitiseWindow(since);
        String key = "changelog:" + project.projectId() + ":" + window + ":" + effectiveLimit;
        return jsonCache.memoize(key, () -> build(window, effectiveLimit));
    }

    private Map<String, Object> build(String window, int limit) {
        Duration duration = parseDuration(window);
        List<Map<String, Object>> all = store.recentlyChanged(project.projectId(), duration, limit);

        // Group by label preserving insertion order so the most-recently-touched label
        // surfaces first. Items inside each group are already sorted desc by lastIngestedAt
        // from the store implementation.
        Map<String, List<Map<String, Object>>> byLabel = new LinkedHashMap<>();
        for (Map<String, Object> row : all) {
            String label = String.valueOf(row.get("label"));
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> groups = new ArrayList<>(byLabel.size());
        for (Map.Entry<String, List<Map<String, Object>>> entry : byLabel.entrySet()) {
            List<Map<String, Object>> all2 = entry.getValue();
            int count = all2.size();
            List<Map<String, Object>> items = count > ITEMS_PER_LABEL
                    ? all2.subList(0, ITEMS_PER_LABEL)
                    : all2;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("label", entry.getKey());
            group.put("count", count);
            group.put("truncated", count > ITEMS_PER_LABEL);
            group.put("items", items);
            groups.add(group);
        }
        // Order groups by recency: the most-recent touch within each label.
        groups.sort(Comparator.<Map<String, Object>, String>comparing(
                g -> firstIngestedAt(g), Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of(
                "projectId", project.projectId(),
                "name", project.name()
        ));
        out.put("generatedAt", Instant.now().toString());
        out.put("since", window);
        out.put("limit", limit);
        out.put("totalTouched", all.size());
        out.put("truncated", all.size() >= limit);
        out.put("groups", groups);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String firstIngestedAt(Map<String, Object> group) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("items");
        if (items == null || items.isEmpty()) return null;
        Object t = items.get(0).get("lastIngestedAt");
        return t == null ? null : t.toString();
    }

    /** Compact duration parser shared with {@link RecentController}. */
    private static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ofDays(7);
        try {
            int n = Integer.parseInt(s.substring(0, s.length() - 1));
            char unit = Character.toLowerCase(s.charAt(s.length() - 1));
            return switch (unit) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default  -> Duration.ofDays(7);
            };
        } catch (Exception e) {
            return Duration.ofDays(7);
        }
    }

    /** Defensive normalisation so the cache key isn't influenced by spurious chars. */
    private static String sanitiseWindow(String s) {
        if (s == null) return "7d";
        String trimmed = s.trim();
        if (trimmed.isEmpty() || trimmed.length() > 8) return "7d";
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!Character.isDigit(c) && c != 'm' && c != 'h' && c != 'd'
                    && c != 'M' && c != 'H' && c != 'D') return "7d";
        }
        return trimmed.toLowerCase();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
