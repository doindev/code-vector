package io.doindev.cvector.cli.scan;

import java.util.List;

/**
 * Structured outcome of a single {@link InProcessScanService#scan} run. Callers consume the
 * named fields directly; the {@link #log} field carries the human-readable progress lines the
 * service emitted (same lines that previously went to stdout when ScanCommand printed them),
 * so the CLI can dump them verbatim and the MCP tool can attach them to its JSON response.
 *
 * <p>Counters that don't apply to a given run (e.g. {@link #staleRemoved} on a bulk load)
 * are {@code 0} rather than {@code null} so JSON serialisation stays branch-free.
 */
public record ScanResult(
        String backend,
        String dbPath,
        String mode,
        int filesScanned,
        int filesSkipped,
        long elapsedMs,
        long parserMs,
        long flushMs,
        int nodesUpserted,
        int edgesUpserted,
        int handlersLinked,
        int callsResolved,
        int staleRemoved,
        List<String> log) {
}
