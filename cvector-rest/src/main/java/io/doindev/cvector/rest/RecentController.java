package io.doindev.cvector.rest;

import io.doindev.cvector.core.store.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Recently-ingested nodes for the dashboard's "what changed since I last looked" panel.
 * Delegates to {@link GraphStore#recentlyChanged} which queries by {@code lastIngestedAt}
 * descending.
 *
 * <p>{@code since} accepts compact human durations: {@code 1h}, {@code 24h}, {@code 7d},
 * {@code 30m}. Default is 24h, which catches an overnight scan.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class RecentController {

    private final GraphStore store;
    private final ActiveProject project;
    private final JsonCache jsonCache;

    public RecentController(GraphStore restGraphStore, ActiveProject activeProject, JsonCache jsonCache) {
        this.store = restGraphStore;
        this.project = activeProject;
        this.jsonCache = jsonCache;
    }

    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] recent(
            @RequestParam(value = "since", defaultValue = "24h") String since,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        int safeLimit = clamp(limit, 1, 250);
        String key = "recent:" + project.projectId() + ":" + since + ":" + safeLimit;
        return jsonCache.memoize(key, () ->
                store.recentlyChanged(project.projectId(), parseDuration(since), safeLimit));
    }

    /**
     * Compact duration parser: {@code 30m}, {@code 24h}, {@code 7d}. Falls back to 24 hours
     * on a malformed value -- the API surface stays user-facing-friendly without throwing 400s
     * that the UI would then have to render.
     */
    private static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ofHours(24);
        try {
            int n = Integer.parseInt(s.substring(0, s.length() - 1));
            char unit = Character.toLowerCase(s.charAt(s.length() - 1));
            return switch (unit) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default  -> Duration.ofHours(24);
            };
        } catch (Exception e) {
            return Duration.ofHours(24);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
