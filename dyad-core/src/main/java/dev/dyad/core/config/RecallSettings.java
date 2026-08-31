package dev.dyad.core.config;

import dev.dyad.core.model.FusionWeights;

/**
 * Everything the Tier 1 ranker reads. Workspace-overridable, because the right half-life for a
 * personal assistant and for a coding agent are not the same number.
 *
 * @param halfLifeDays recency decay; 180 is a deliberately conservative default, not a tuned one
 * @param threshold cut applied to the <em>fused</em> score, not to the semantic score alone
 * @param oversample how many candidates each signal path fetches before fusion
 * @param entityTopK entity neighbours considered for the boost
 * @param entitySimCut hard floor below which an entity match contributes nothing
 */
public record RecallSettings(
        FusionWeights weights,
        double halfLifeDays,
        double threshold,
        int oversample,
        int entityTopK,
        double entitySimCut) {

    public static final RecallSettings DEFAULT =
            new RecallSettings(FusionWeights.DEFAULT, 180.0, 0.0, 4, 10, 0.5);

    public RecallSettings withWeights(FusionWeights w) {
        return new RecallSettings(w, halfLifeDays, threshold, oversample, entityTopK, entitySimCut);
    }

    public RecallSettings withThreshold(double t) {
        return new RecallSettings(weights, halfLifeDays, t, oversample, entityTopK, entitySimCut);
    }

    public RecallSettings withHalfLifeDays(double days) {
        return new RecallSettings(weights, days, threshold, oversample, entityTopK, entitySimCut);
    }
}
