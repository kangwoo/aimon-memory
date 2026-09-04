package at.aimon.memory.core.key;

import at.aimon.memory.core.MemoryException;

/**
 * Colon-delimited key encoding.
 *
 * <p>Peer and session names are user-supplied, so they can contain the delimiter. Only {@code %} and
 * {@code :} are escaped — everything else survives verbatim, which keeps keys readable in logs and
 * in the {@code queue.work_unit_key} column.
 */
final class Segments {

    private Segments() {
    }

    static String encode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '%' -> sb.append("%25");
                case ':' -> sb.append("%3A");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String decode(String encoded) {
        if (encoded.indexOf('%') < 0) {
            return encoded;
        }
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '%') {
                sb.append(c);
                continue;
            }
            if (i + 2 >= encoded.length()) {
                throw new MemoryException("bad_key", "truncated escape in key segment: " + encoded);
            }
            String hex = encoded.substring(i + 1, i + 3);
            char decoded = switch (hex) {
                case "25" -> '%';
                case "3A", "3a" -> ':';
                default -> throw new MemoryException("bad_key", "unsupported escape %" + hex + " in key: " + encoded);
            };
            sb.append(decoded);
            i += 2;
        }
        return sb.toString();
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MemoryException("bad_key", field + " must not be blank");
        }
        return value;
    }
}
