package io.doindev.cvector.embedded;

import io.doindev.cvector.core.NodeKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable content hash for buffered Kuzu node writes. Used to skip MERGEs at re-scan time when a
 * node's schema-relevant properties haven't changed since the last scan.
 *
 * <p>The hash is computed from {@code (label, key.id, sorted stable props)}. Two columns are
 * <em>intentionally excluded</em>:
 * <ul>
 *   <li>{@code lastIngestedAt} — changes per scan; including it would invalidate every hash.</li>
 *   <li>{@code contentHash} itself — would create a self-reference loop on subsequent scans.</li>
 * </ul>
 *
 * <p>Properties absent from {@code raw} are skipped entirely (not hashed as null), so a property
 * the parser stopped emitting doesn't flip the hash relative to a row whose stored value is null.
 * Two rows are "the same" exactly when their parser-emitted props are equal key-by-key.
 */
final class KuzuNodeHash {

    private KuzuNodeHash() {}

    static String compute(NodeKey key, Map<String, Object> raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(key.label().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(key.fqName().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            // Sorted iteration so map ordering doesn't affect the hash.
            TreeMap<String, Object> sorted = raw instanceof TreeMap<?, ?>
                    ? (TreeMap<String, Object>) raw
                    : new TreeMap<>(raw == null ? Map.of() : raw);
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                String k = e.getKey();
                if ("lastIngestedAt".equals(k) || "contentHash".equals(k)) continue;
                Object v = e.getValue();
                if (v == null) continue;
                md.update(k.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '=');
                md.update(v.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            // 16-char hex prefix — same width KeyHash uses for NodeKey.id().
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
