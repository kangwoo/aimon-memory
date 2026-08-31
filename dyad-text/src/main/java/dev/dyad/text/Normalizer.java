package dev.dyad.text;

import java.util.Locale;

/**
 * The {@code content_norm} rule: trim ASCII whitespace, then lowercase.
 *
 * <p>The rule exists twice — here and as {@link #SQL_EXPRESSION} — and a parity test compares them
 * against a real Postgres for every case that has ever caused trouble. That test is not ceremony: if
 * the two drift, dedup stage 2 silently stops matching and the store accumulates duplicates with no
 * error anywhere.
 *
 * <p>Two details are load-bearing.
 *
 * <p><b>The trim set is explicit.</b> {@code String.strip()} removes every Unicode whitespace
 * character; Postgres {@code btrim(x)} with no second argument removes only the space. Tabs and
 * newlines fall in the gap, which is where content pasted from a document lands. Both sides are
 * pinned to the same ASCII set rather than left to their defaults.
 *
 * <p><b>{@link Locale#ROOT} is not cosmetic.</b> A Turkish default locale maps {@code I} to
 * {@code ı}, and the correspondence with Postgres breaks for that deployment only.
 */
public final class Normalizer {

    /** Space, tab, newline, carriage return, form feed, vertical tab. */
    public static final String TRIM_CHARS = " \t\n\r\f\u000B";

    /**
     * The same rule in SQL. Format with the column expression, e.g.
     * {@code SQL_EXPRESSION.formatted("content")}.
     */
    public static final String SQL_EXPRESSION = "lower(btrim(%s, E' \\t\\n\\r\\f\\x0B'))";

    private Normalizer() {}

    public static String normalize(String content) {
        if (content == null) {
            return "";
        }
        return trim(content).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }
}
