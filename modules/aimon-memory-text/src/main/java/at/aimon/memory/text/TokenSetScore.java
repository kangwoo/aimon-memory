package at.aimon.memory.text;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Information score used by dedup stage 3 to decide which of two near-identical phrasings survives.
 *
 * <p>{@code |tokens| + weight × |distinct tokens|} — length alone would let a padded restatement win,
 * so distinct tokens carry ten times the weight. A tie goes to the newcomer, which keeps the store
 * converging on the most recent wording of a fact that keeps being restated.
 */
public final class TokenSetScore {

    private TokenSetScore() {
    }

    public static int score(List<String> tokens, int uniqueTokenWeight) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        Set<String> distinct = new HashSet<>(tokens);
        return tokens.size() + uniqueTokenWeight * distinct.size();
    }
}
