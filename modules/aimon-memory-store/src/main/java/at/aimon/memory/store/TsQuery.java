package at.aimon.memory.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@code tsquery} that ORs the analysed query terms.
 *
 * <p>{@code plainto_tsquery} ANDs everything, which for recall is the wrong default — a five-word
 * question would only match a conclusion containing all five. Requiring every term turns a ranking
 * problem into a filtering one and drops the candidates fusion exists to reorder.
 *
 * <p>Terms are stripped of tsquery operator characters rather than escaped. The input is already
 * analyzer output, so anything removed here was not a real token to begin with.
 */
public final class TsQuery {

    private TsQuery() {
    }

    public static String orOf(List<String> terms) {
        List<String> safe = new ArrayList<>(terms.size());
        for (String term : terms) {
            String cleaned = sanitize(term);
            if (!cleaned.isEmpty() && !safe.contains(cleaned)) {
                safe.add(cleaned);
            }
        }
        return String.join(" | ", safe);
    }

    private static String sanitize(String term) {
        StringBuilder sb = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
