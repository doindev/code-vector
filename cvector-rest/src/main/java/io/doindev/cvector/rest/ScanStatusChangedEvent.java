package io.doindev.cvector.rest;

import java.time.Instant;

/**
 * Published when a dashboard-triggered scan transitions in or out of the running state.
 * Carries enough metadata for the SSE forwarder to push a meaningful payload without
 * a follow-up REST call, but views can still hit {@code /api/scans/status} for the
 * authoritative snapshot if they need details we don't surface here.
 */
public record ScanStatusChangedEvent(
        String state,           // "started" | "finished"
        String action,          // e.g. "scan" or "scan:incremental"
        long pid,
        Integer exitCode,       // null for "started"
        Instant timestamp
) {
    public static ScanStatusChangedEvent started(String action, long pid) {
        return new ScanStatusChangedEvent("started", action, pid, null, Instant.now());
    }
    public static ScanStatusChangedEvent finished(String action, long pid, int exitCode) {
        return new ScanStatusChangedEvent("finished", action, pid, exitCode, Instant.now());
    }
}
