package io.doindev.cvector.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Sibling of {@link GraphReadCache}, but it stores the already-Jackson-serialised {@code byte[]}
 * instead of the original object graph. The hottest read endpoints ({@code /api/onboard},
 * {@code /api/wiki}, {@code /api/rules}, {@code /api/communities}, {@code /api/service-links})
 * spend ~100–150 ms of their warm latency on Jackson's per-call object→bytes work; storing the
 * bytes once and replaying them on hits collapses that overhead and lets Spring write the
 * payload straight into the response without going through a MessageConverter.
 *
 * <p>Same TTL + soft-cap + scan-flush semantics as GraphReadCache, intentionally so a single
 * {@link GraphMutatedEvent} clears both layers in lock-step. Entries are keyed by an opaque
 * string the caller chooses (typically {@code endpoint:projectId[:params]}); two calls with
 * the same key but different computed objects would corrupt each other, so callers must
 * include every dimension that changes the result in the key.
 */
@Component
@ConditionalOnWebApplication
public class JsonCache {

    /** Same default TTL as GraphReadCache so the two layers expire together by default. */
    public static final Duration DEFAULT_TTL = GraphReadCache.DEFAULT_TTL;

    /** Soft cap on entries; mirrors GraphReadCache's bound so symbol-keyed endpoints stay bounded. */
    private static final int MAX_ENTRIES = 4096;

    private final ObjectMapper mapper;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();
    /** Hits + misses since last {@link #invalidateAll}. Same pattern as {@link GraphReadCache}. */
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

    public JsonCache(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Memoise the JSON bytes for {@code compute()} under {@code key}. If the entry is fresh,
     * the cached bytes are returned without touching {@code compute}; on miss or expiry we
     * compute the object, serialise it once, and store the bytes for the next caller.
     */
    public byte[] memoize(String key, Supplier<?> compute) {
        return memoize(key, DEFAULT_TTL, compute);
    }

    public byte[] memoize(String key, Duration ttl, Supplier<?> compute) {
        Instant now = Instant.now();
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt.isAfter(now)) {
            hits.incrementAndGet();
            return hit.bytes;
        }
        misses.incrementAndGet();
        Object value = compute.get();
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise cache value for " + key, e);
        }
        if (cache.size() >= MAX_ENTRIES) sweep(now);
        cache.put(key, new Entry(bytes, now.plus(ttl)));
        return bytes;
    }

    /** Hit / miss counters reset on every {@link #invalidateAll}. */
    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }

    public void invalidateAll() {
        cache.clear();
        hits.set(0);
        misses.set(0);
    }

    /** {@link GraphMutatedEvent} clears the JSON cache so the next read recomputes. */
    @EventListener
    public void onGraphMutated(GraphMutatedEvent event) {
        invalidateAll();
    }

    /** Snapshot stat for /api/health/cache or debugging. */
    public int size() { return cache.size(); }

    private void sweep(Instant now) {
        cache.values().removeIf(e -> e.expiresAt.isBefore(now));
        if (cache.size() < MAX_ENTRIES) return;
        int toRemove = Math.max(1, cache.size() / 4);
        cache.entrySet().stream()
                .sorted((a, b) -> a.getValue().expiresAt.compareTo(b.getValue().expiresAt))
                .limit(toRemove)
                .map(java.util.Map.Entry::getKey)
                .forEach(cache::remove);
    }

    private record Entry(byte[] bytes, Instant expiresAt) {}
}
