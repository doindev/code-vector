package io.doindev.cvector.core.store;

import io.doindev.cvector.core.GraphEvent;

import java.util.function.Consumer;

/**
 * Backend-agnostic sink for {@link GraphEvent} streams. Both the Neo4j {@code Ingestor} and the
 * embedded {@code KuzuIngestor} implement this — parser code that targets the interface works
 * against either backend. Acquire one via {@link GraphStore#openIngestor()}, stream events
 * through {@code accept}, then {@code flush} (or close — close calls flush).
 *
 * <p>Each implementation owns a connection to its backend; callers must close the ingestor
 * (try-with-resources is fine) so buffered writes don't get dropped.
 */
public interface GraphIngestor extends Consumer<GraphEvent>, AutoCloseable {

    /** Flush all buffered writes to the backend. Idempotent — calling twice is a no-op. */
    void flush();

    /** Total NodeUpsert events accepted (post-dedup for backends that dedupe). */
    int totalNodes();

    /** Total EdgeUpsert events accepted (post-dedup for backends that dedupe). */
    int totalEdges();

    @Override
    void close();
}
