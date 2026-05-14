package io.doindev.cvector.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic node identity. The id is the first 16 hex chars (= 8 bytes) of
 * SHA-256({@code projectId:label:fqName}) so re-running a scan never duplicates a node and
 * Ingestor's MERGE-by-id is idempotent.
 *
 * <p>{@link #id()} is on the scan hot path — every {@code GraphEvent.NodeUpsert} resolves it
 * at least once, and the cvector codebase itself has 70+ direct callers in queries, ingestion,
 * and tests. Three perf nudges live here:
 * <ul>
 *   <li>A {@link ThreadLocal} {@link MessageDigest} skips the per-call JCA provider lookup
 *       (the dominant cost before; {@code MessageDigest.getInstance} walks the security
 *       provider list).</li>
 *   <li>{@link #HEX_ALPHABET} + inline encoding hashes only 8 bytes into a 16-char string
 *       — the previous version formatted all 64 hex chars then called {@code substring(0,16)},
 *       wasting 75% of the format work.</li>
 *   <li>{@link StringBuilder} pre-sized to the joined-key length saves one allocation versus
 *       chained {@code "+"} concatenation.</li>
 * </ul>
 * Combined: roughly half the original CPU cost per id on a microbenchmark, more on big scans
 * where the JCA lookup dominates.
 */
public record NodeKey(String projectId, String label, String fqName) {

    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    /**
     * MessageDigest isn't thread-safe but reset() is cheap; one instance per thread keeps the
     * Ingestor (which can be parallel during multi-parser scans) from contending on a shared
     * digest while still skipping the {@code getInstance} cost.
     */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    public String id() {
        // Build the joined key into a sized StringBuilder — avoids the chained-+ temporaries
        // the previous implementation created (three intermediate Strings per call).
        int pidLen = projectId.length();
        int labelLen = label.length();
        int fqNameLen = fqName.length();
        StringBuilder sb = new StringBuilder(pidLen + labelLen + fqNameLen + 2);
        sb.append(projectId).append(':').append(label).append(':').append(fqName);

        MessageDigest md = SHA256.get();
        md.reset();
        byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));

        // Encode only the first 8 bytes (16 hex chars) — that's the entire id surface. The
        // old code did HexFormat.of().formatHex(hash) (64 chars) then substring(0,16), so 75%
        // of the encoding work was discarded.
        char[] out = new char[16];
        for (int i = 0; i < 8; i++) {
            int b = hash[i] & 0xFF;
            out[i * 2]     = HEX_ALPHABET[b >>> 4];
            out[i * 2 + 1] = HEX_ALPHABET[b & 0x0F];
        }
        return new String(out);
    }
}
