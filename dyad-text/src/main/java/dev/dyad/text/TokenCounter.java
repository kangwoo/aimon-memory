package dev.dyad.text;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;

/**
 * Token counting against {@code o200k_base}.
 *
 * <p>Used for three separate budgets — the 512-token batch gate, the {@code context()} 40/60 split,
 * and the dialectic history cap — so it is worth having one implementation whose numbers everyone
 * agrees on rather than three estimates.
 */
public final class TokenCounter {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private TokenCounter() {}

    public static int count(String text) {
        return text == null || text.isEmpty() ? 0 : ENCODING.countTokens(text);
    }

    /** Cut to at most {@code maxTokens}, on a token boundary rather than a character one. */
    public static String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        List<Integer> tokens = ENCODING.encode(text).boxed();
        if (tokens.size() <= maxTokens) {
            return text;
        }
        com.knuddels.jtokkit.api.IntArrayList kept = new com.knuddels.jtokkit.api.IntArrayList(maxTokens);
        for (int i = 0; i < maxTokens; i++) {
            kept.add(tokens.get(i));
        }
        return ENCODING.decode(kept);
    }
}
