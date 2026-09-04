package at.aimon.memory.store.dedup;

import java.util.List;

import at.aimon.memory.core.config.DedupSettings;
import at.aimon.memory.text.TokenSetScore;

/**
 * The stage-3 decision, isolated from any SQL so it can be pinned by fixtures at its boundaries.
 *
 * <p>Two conclusions are within the cosine threshold; one of them has to go. The rule is information
 * content, not recency and not length: {@code |tokens| + 10 × |distinct tokens|}. Length alone would
 * let "alice works in Gangnam, Seoul, and she works there" beat "alice works in Gangnam, Seoul".
 *
 * <p>Ties go to the newcomer. That keeps the store converging on the most recent phrasing of a fact
 * that keeps being restated, instead of freezing the first wording forever.
 */
public final class DedupPolicy {

    private final DedupSettings settings;

    public DedupPolicy(DedupSettings settings) {
        this.settings = settings;
    }

    public boolean isNearDuplicate(double cosineDistance) {
        return cosineDistance <= settings.cosineDistanceMax();
    }

    /** True when the incoming conclusion should replace the existing one. */
    public boolean newReplacesExisting(List<String> newTokens, List<String> existingTokens) {
        int incoming = TokenSetScore.score(newTokens, settings.uniqueTokenWeight());
        int existing = TokenSetScore.score(existingTokens, settings.uniqueTokenWeight());
        return incoming >= existing;
    }

    public DedupSettings settings() {
        return settings;
    }
}
