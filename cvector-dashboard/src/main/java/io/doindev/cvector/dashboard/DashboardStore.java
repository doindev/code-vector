package io.doindev.cvector.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JSON-file backed store for dashboard state. Lives at {@code ~/.cvector/dashboard.json}
 * (one file shared across projects -- keeps monitors and schedules visible across
 * project switches and is trivially user-editable).
 *
 * <p>Concurrency: a single instance is wired as a Spring bean and serialised through a
 * {@link ReentrantReadWriteLock}. Read paths use the read lock; mutations take the write
 * lock + write the file atomically via a tmp-then-rename to avoid torn JSON on crash.
 *
 * <p>This is deliberately not Spring Data / JPA -- the data set is tiny (tens of rows),
 * the file is human-editable, and we want zero external DB dependency for the dashboard
 * module. If a future feature needs richer querying we can migrate without changing the
 * controller surface.
 */
@Component
public class DashboardStore {

    private static final Logger log = LoggerFactory.getLogger(DashboardStore.class);

    public record Monitor(String id, String path, boolean enabled, String addedAt) {
        public static Monitor create(String path) {
            return new Monitor(UUID.randomUUID().toString(), path, true, Instant.now().toString());
        }
    }

    public record Schedule(String id, String name, String cron, String action, boolean enabled) {
        public static Schedule create(String name, String cron, String action) {
            return new Schedule(UUID.randomUUID().toString(), name, cron, action, true);
        }
    }

    public record QueryRecord(
            String id, String cypher, String ranAt,
            boolean ok, Integer rowCount, String errorMessage
    ) {
        public static QueryRecord ofSuccess(String cypher, int rowCount) {
            return new QueryRecord(UUID.randomUUID().toString(), cypher,
                    Instant.now().toString(), true, rowCount, null);
        }
        public static QueryRecord ofError(String cypher, String message) {
            return new QueryRecord(UUID.randomUUID().toString(), cypher,
                    Instant.now().toString(), false, null, message);
        }
    }

    /** Max query history rows retained on disk. Older entries are evicted FIFO. */
    private static final int MAX_QUERY_HISTORY = 50;

    /** On-disk shape. Jackson tolerates missing fields so older dashboard.json files keep loading. */
    public record State(
            List<Monitor> monitors,
            List<Schedule> schedules,
            List<QueryRecord> queries,
            Map<String, Object> settings
    ) {
        public static State empty() {
            return new State(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path storeFile;
    private State state;

    public DashboardStore() {
        this(defaultPath());
    }

    DashboardStore(Path storeFile) {
        this.storeFile = storeFile;
        this.state = load(storeFile);
    }

    private static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), ".cvector", "dashboard.json");
    }

    private State load(Path file) {
        if (!Files.exists(file)) return State.empty();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return State.empty();
            State loaded = mapper.readValue(bytes, State.class);
            return new State(
                    loaded.monitors() != null ? new ArrayList<>(loaded.monitors()) : new ArrayList<>(),
                    loaded.schedules() != null ? new ArrayList<>(loaded.schedules()) : new ArrayList<>(),
                    loaded.queries() != null ? new ArrayList<>(loaded.queries()) : new ArrayList<>(),
                    loaded.settings() != null ? new LinkedHashMap<>(loaded.settings()) : new LinkedHashMap<>()
            );
        } catch (IOException e) {
            log.warn("dashboard store at {} is unreadable; starting fresh ({})", file, e.getMessage());
            return State.empty();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName().toString() + ".tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("failed to persist dashboard store: {}", e.getMessage());
        }
    }

    // ---- Monitors ----

    public List<Monitor> monitors() {
        lock.readLock().lock();
        try { return List.copyOf(state.monitors()); } finally { lock.readLock().unlock(); }
    }

    public Monitor addMonitor(String path) {
        lock.writeLock().lock();
        try {
            Monitor m = Monitor.create(path);
            state.monitors().add(m);
            persist();
            return m;
        } finally { lock.writeLock().unlock(); }
    }

    public boolean removeMonitor(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = state.monitors().removeIf(m -> m.id().equals(id));
            if (removed) persist();
            return removed;
        } finally { lock.writeLock().unlock(); }
    }

    public Monitor toggleMonitor(String id) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < state.monitors().size(); i++) {
                Monitor m = state.monitors().get(i);
                if (m.id().equals(id)) {
                    Monitor updated = new Monitor(m.id(), m.path(), !m.enabled(), m.addedAt());
                    state.monitors().set(i, updated);
                    persist();
                    return updated;
                }
            }
            return null;
        } finally { lock.writeLock().unlock(); }
    }

    // ---- Schedules ----

    public List<Schedule> schedules() {
        lock.readLock().lock();
        try { return List.copyOf(state.schedules()); } finally { lock.readLock().unlock(); }
    }

    public Schedule addSchedule(String name, String cron, String action) {
        lock.writeLock().lock();
        try {
            Schedule s = Schedule.create(name, cron, action);
            state.schedules().add(s);
            persist();
            return s;
        } finally { lock.writeLock().unlock(); }
    }

    public boolean removeSchedule(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = state.schedules().removeIf(s -> s.id().equals(id));
            if (removed) persist();
            return removed;
        } finally { lock.writeLock().unlock(); }
    }

    public Schedule toggleSchedule(String id) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < state.schedules().size(); i++) {
                Schedule s = state.schedules().get(i);
                if (s.id().equals(id)) {
                    Schedule updated = new Schedule(s.id(), s.name(), s.cron(), s.action(), !s.enabled());
                    state.schedules().set(i, updated);
                    persist();
                    return updated;
                }
            }
            return null;
        } finally { lock.writeLock().unlock(); }
    }

    // ---- Query history ----

    /** Newest-first. Capped at {@link #MAX_QUERY_HISTORY} to keep dashboard.json under a few KB. */
    public List<QueryRecord> queries() {
        lock.readLock().lock();
        try { return List.copyOf(state.queries()); } finally { lock.readLock().unlock(); }
    }

    public QueryRecord addQuery(QueryRecord q) {
        lock.writeLock().lock();
        try {
            // Insert at head so the UI sees most recent first without resorting on every fetch.
            state.queries().add(0, q);
            // FIFO evict from the tail when capacity is exceeded.
            while (state.queries().size() > MAX_QUERY_HISTORY) {
                state.queries().remove(state.queries().size() - 1);
            }
            persist();
            return q;
        } finally { lock.writeLock().unlock(); }
    }

    public boolean removeQuery(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = state.queries().removeIf(q -> q.id().equals(id));
            if (removed) persist();
            return removed;
        } finally { lock.writeLock().unlock(); }
    }

    public void clearQueries() {
        lock.writeLock().lock();
        try { state.queries().clear(); persist(); } finally { lock.writeLock().unlock(); }
    }

    // ---- Settings (free-form map; persistence-friendly for arbitrary UI prefs) ----

    public Map<String, Object> settings() {
        lock.readLock().lock();
        try { return Map.copyOf(state.settings()); } finally { lock.readLock().unlock(); }
    }

    public void putSettings(Map<String, Object> incoming) {
        lock.writeLock().lock();
        try {
            state.settings().putAll(incoming);
            persist();
        } finally { lock.writeLock().unlock(); }
    }
}
