package org.payment.model.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * Same request -> same hash; any difference -> different hash.
 * Built from the method, path and meaningful fields, so JSON spacing or field order can't change it.
 */
public final class RequestHash {

    private RequestHash() {}

    public static String of(String method, String path, Object... fields) {
        StringJoiner joined = new StringJoiner("|");
        joined.add(method).add(path);
        for (Object f : fields) {
            joined.add(String.valueOf(f));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }
}