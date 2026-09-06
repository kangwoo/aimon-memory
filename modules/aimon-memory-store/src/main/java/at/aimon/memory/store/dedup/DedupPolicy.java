package at.aimon.memory.store.dedup;

import java.util.List;

import at.aimon.memory.core.config.DedupSettings;
import at.aimon.memory.text.TokenSetScore;

/**
 * The stage-3 decision, isolated from any SQL so it can be pinned by fixtures at its boundaries.
 *
 * <p>Two conclusions are within the cosine threshold; one of them has to go. The rule is information
 * content, not recency and not length: {@code |tokens| + 10 × |distinct tokens|}. Length alone would
 * let "alice works at a bank, alice works at a bank" beat "alice works at a Gangnam bank" — ten
 * tokens against six — where the weight puts the specific one ahead, 66 to 60.
 *
 * <p>The example has to repeat tokens to show anything, and the one that stood here until now did
 * not: "alice works in Gangnam, Seoul, and she works there" against "alice works in Gangnam, Seoul"
 * is padding whose every added word is also a new distinct token, so the weight it was meant to
 * illustrate <em>funds</em> the longer string instead of penalising it — 89 to 55 at the default
 * weight, the opposite of what the comment claimed. The formula was never wrong; the example was.
 * {@code DedupPolicyTest} pins the real behaviour at both weights.
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
