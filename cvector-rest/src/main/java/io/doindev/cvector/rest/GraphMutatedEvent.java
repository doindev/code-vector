package io.doindev.cvector.rest;

/**
 * Published when something has likely mutated the graph (a scan completed, a watcher
 * fired an incremental ingest, etc.). {@link GraphReadCache} listens and flushes; other
 * subsystems can hook in later (e.g. SSE pushers for live dashboards). Publishers should
 * fire this only after the mutation has actually committed, so cache reads after the
 * event see post-mutation data.
 */
public record GraphMutatedEvent(String source) {
    public static GraphMutatedEvent fromScan() { return new GraphMutatedEvent("scan"); }
    public static GraphMutatedEvent fromSchedule() { return new GraphMutatedEvent("schedule"); }
    public static GraphMutatedEvent fromMonitor() { return new GraphMutatedEvent("monitor"); }
}
