package io.doindev.cvector.watcher;

import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.core.store.GraphIngestor;
import io.doindev.cvector.core.store.GraphStore;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live file-system watcher that re-indexes changed files into the active {@link GraphStore}.
 * Backend-agnostic — works against Neo4j or the embedded KuzuDB store.
 *
 * <p>Events are debounced (default 250 ms) and processed in a single batch per flush. Each flush
 * opens a fresh {@link GraphIngestor} so buffered writes commit atomically per debounce window.
 * File deletes route through {@link GraphStore#deleteFileSubtree} which cascades to all CONTAINS
 * children (Neo4j: variable-length DETACH DELETE; Kuzu: Java BFS over CONTAINS edges).
 */
public class CvectorWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CvectorWatcher.class);

    private final ProjectContext ctx;
    private final List<Parser> parsers;
    private final GraphStore store;
    private final long debounceMillis;

    private final Map<Path, DirectoryChangeEvent.EventType> pending = new LinkedHashMap<>();
    private final Object pendingLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cvector-watcher");
        t.setDaemon(true);
        return t;
    });

    private DirectoryWatcher watcher;
    private volatile boolean flushScheduled;
    private volatile int totalFiles;
    private volatile int totalDeletes;
    private volatile int transientFailures;

    public CvectorWatcher(ProjectContext ctx, List<Parser> parsers, GraphStore store, long debounceMillis) {
        this.ctx = ctx;
        this.parsers = parsers;
        this.store = store;
        this.debounceMillis = debounceMillis;
    }

    public void start() throws IOException {
        for (Parser p : parsers) p.prepare(ctx);
        watcher = DirectoryWatcher.builder()
                .path(ctx.rootPath())
                .listener(this::onEvent)
                .build();
        watcher.watchAsync();
        log.info("watching {} (backend: {})", ctx.rootPath(), store.displayUri());
    }

    public int totalFilesProcessed() { return totalFiles; }
    public int totalDeletes() { return totalDeletes; }
    public int transientFailures() { return transientFailures; }

    private void onEvent(DirectoryChangeEvent event) {
        Path p = event.path();
        if (isIgnored(p)) return;
        synchronized (pendingLock) {
            pending.merge(p, event.eventType(), (older, newer) -> {
                if (newer == DirectoryChangeEvent.EventType.DELETE) return DirectoryChangeEvent.EventType.DELETE;
                return newer;
            });
            if (!flushScheduled) {
                flushScheduled = true;
                scheduler.schedule(this::flush, debounceMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flush() {
        Map<Path, DirectoryChangeEvent.EventType> snapshot;
        synchronized (pendingLock) {
            snapshot = new LinkedHashMap<>(pending);
            pending.clear();
            flushScheduled = false;
        }
        if (snapshot.isEmpty()) return;
        try (GraphIngestor ingestor = store.openIngestor()) {
            for (Map.Entry<Path, DirectoryChangeEvent.EventType> e : snapshot.entrySet()) {
                Path p = e.getKey();
                DirectoryChangeEvent.EventType type = e.getValue();
                try {
                    if (type == DirectoryChangeEvent.EventType.DELETE) {
                        handleDelete(p);
                        totalDeletes++;
                    } else {
                        handleUpsert(p, ingestor);
                    }
                } catch (RuntimeException perFile) {
                    log.warn("skipping {} ({}); will retry on next change event: {}",
                            ctx.rootPath().relativize(p), type, perFile.getMessage());
                    transientFailures++;
                }
            }
            try {
                ingestor.flush();
            } catch (RuntimeException flushEx) {
                log.warn("ingestor flush failed: {}", flushEx.getMessage());
            }
        } catch (RuntimeException outer) {
            log.warn("flush cycle failed: {}", outer.getMessage());
        }
    }

    private void handleUpsert(Path file, GraphIngestor ingestor) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            log.debug("not a regular file (deleted before flush?): {}", file);
            return;
        }
        if (!Files.isReadable(file)) {
            log.info("not readable yet ({}); will retry on next change event",
                    ctx.rootPath().relativize(file));
            transientFailures++;
            return;
        }
        for (Parser p : parsers) {
            if (!p.accepts(file)) continue;
            try {
                p.parse(file, ctx, ingestor);
                totalFiles++;
                log.info("re-indexed {} ({})", ctx.rootPath().relativize(file), p.name());
            } catch (RuntimeException parseEx) {
                log.warn("parse failed for {} ({}); will retry on next change event: {}",
                        ctx.rootPath().relativize(file), p.name(), parseEx.getMessage());
                transientFailures++;
            }
            return;
        }
    }

    private void handleDelete(Path file) {
        String rel = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        try {
            int removed = store.deleteFileSubtree(ctx.projectId(), rel);
            if (removed > 0) {
                log.info("removed {} node(s) for deleted file {}", removed, rel);
            } else {
                log.debug("no graph state to remove for {}", rel);
            }
        } catch (RuntimeException ex) {
            log.warn("failed to remove nodes for {}; will retry on next change: {}", rel, ex.getMessage());
            transientFailures++;
        }
    }

    private static boolean isIgnored(Path p) {
        for (Path part : p) {
            String name = part.getFileName().toString();
            if (name.equals("target") || name.equals("build") || name.equals("node_modules")
                    || name.equals(".git") || name.equals(".cvector")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        try {
            if (watcher != null) watcher.close();
        } catch (IOException ignored) {
        }
        scheduler.shutdown();
    }
}
