package io.doindev.cvector.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Tiny TTL cache for read-heavy REST endpoints. Memoises the expensive {@link
 * io.doindev.cvector.core.store.GraphStore} aggregations ({@code onboardSummary},
 * {@code healthRollup}, {@code serviceLinks}, {@code methodCallGraph}, etc.) so polling
 * dashboard views don't hammer Kuzu on every refresh. The graph only changes when a scan
 * lands; a 30 s default TTL gives the dashboard sub-second response on cache hits without
 * masking real updates for more than half a minute.
 *
 * <p>Entries are keyed by an opaque string the caller chooses — typically
 * {@code endpoint:projectId[:param=value...]}. Values are stored as {@link Object} so a
 * single cache instance can hold heterogeneous result shapes; callers cast in the
 * {@code Supplier} return type.
 *
 * <p>Concurrency: the underlying {@link ConcurrentHashMap} handles the get/put race; we
 * accept that two callers may both miss and re-compute simultaneously (the duplicate work
 * is bounded by the call site and avoids the lock-contention that {@code computeIfAbsent}
 * would introduce when the supplier itself is slow).
 */
@Component
@ConditionalOnWebApplication
public class GraphReadCache {

    /** Default TTL. Polling cadences are 2/3/10/30 s; 30 s lets the 10 s pollers cache-hit ~2/3 of the time. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    /** Memoise {@code compute} under {@code key} using the default TTL. */
    public <T> T memoize(String key, Supplier<T> compute) {
        return memoize(key, DEFAULT_TTL, compute);
    }

    @SuppressWarnings("unchecked")
    public <T> T memoize(String key, Duration ttl, Supplier<T> compute) {
        Instant now = Instant.now();
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt.isAfter(now)) return (T) hit.value;
        T value = compute.get();
        cache.put(key, new Entry(value, now.plus(ttl)));
        return value;
    }

    /** Drop all cached entries. Used after a scan completes so views see fresh data immediately. */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Spring event hook: any publisher firing a {@link GraphMutatedEvent} (scan finished,
     * watcher applied changes, schedule kicked an ingest) triggers a full flush. We don't
     * try to be granular about which entries are stale — the cost of recomputing a few
     * cached aggregates is small compared to the confusion of half-stale dashboards.
     */
    @EventListener
    public void onGraphMutated(GraphMutatedEvent event) {
        invalidateAll();
    }

    /** Cache snapshot stats for /api/health/cache or debugging. */
    public int size() { return cache.size(); }

    private record Entry(Object value, Instant expiresAt) {}
}
