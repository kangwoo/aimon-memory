package dev.dyad.text;

import dev.dyad.core.DyadException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 of the normalised content, lowercase hex. Stage 1 of dedup is an index lookup on this. */
public final class ContentHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentHash() {}

    public static String of(String contentNorm) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new DyadException("no_sha256", "SHA-256 unavailable", e);
        }
        byte[] out = digest.digest(contentNorm.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[out.length * 2];
        for (int i = 0; i < out.length; i++) {
            hex[i * 2] = HEX[(out[i] >> 4) & 0xF];
            hex[i * 2 + 1] = HEX[out[i] & 0xF];
        }
        return new String(hex);
    }
}
