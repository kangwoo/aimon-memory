package dev.dyad.core.id;

import java.security.SecureRandom;

/**
 * 21-character URL-safe identifier, the same shape both source systems use.
 *
 * <p>64-symbol alphabet means 6 bits per character and no modulo bias — every byte is masked to
 * exactly one symbol, so the generator needs a single random read.
 */
public final class NanoId {

    public static final int LENGTH = 21;
    private static final char[] ALPHABET =
            "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NanoId() {}

    public static String generate() {
        byte[] bytes = new byte[LENGTH];
        RANDOM.nextBytes(bytes);
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[bytes[i] & 0x3F];
        }
        return new String(out);
    }

    public static boolean isValid(String id) {
        if (id == null || id.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (indexOf(id.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
