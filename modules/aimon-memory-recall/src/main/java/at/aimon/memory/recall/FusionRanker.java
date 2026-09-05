package at.aimon.memory.recall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import at.aimon.memory.core.config.RecallSettings;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.Explain;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.recall.signal.EntityBoost;
import at.aimon.memory.recall.signal.KeywordSignal;
import at.aimon.memory.recall.signal.RecencySignal;
import at.aimon.memory.recall.signal.ReinforcementSignal;

/**
 * Combines the six signals into one score.
 *
 * <p>Two corrections to the formula this is derived from, and they are the reason the layer exists.
 * That formula is mem0's, which is Apache-2.0 and permits it; the derivation is recorded in ADR 0005
 * and the weights themselves carry the same note in {@code FusionWeights}. "The original" below is
 * mem0 in both places.
 *
 * <p><b>The denominator is constant.</b> Weights sum to 1.00 and stay there whether or not a signal
 * produced anything. The original rescaled by how many stores answered — 1.0, 2.0 or 2.5 — so the
 * same conclusion scored differently depending on configuration, and scores could not be compared
 * across deployments or against a fixed threshold. Here a missing signal contributes zero: the score
 * drops honestly, and the ordering among candidates is untouched.
 *
 * <p><b>The threshold applies to the fused score.</b> The original cut on semantic similarity alone,
 * so a conclusion that a keyword query matched exactly could be discarded before fusion ever saw it.
 * That is precisely the case where the keyword signal was doing its job.
 *
 * <p>Ranking is a pure function of its inputs — including {@code now}, which is passed rather than
 * read — so a fixture can pin it to six decimal places.
 */
public final class FusionRanker {

    private final RecallSettings settings;

    public FusionRanker(RecallSettings settings) {
        this.settings = settings;
    }

    /**
     * @param semantic cosine similarity per conclusion id, missing means zero
     * @param keywordRaw raw BM25 per conclusion id, missing means zero
     */
    public List<ScoredConclusion> rank(List<Conclusion> candidates, Map<String, Double> semantic,
            Map<String, Double> keywordRaw, EntityBoost.Result entities, int queryTermCount, Instant now, int limit) {

        List<ScoredConclusion> scored = new ArrayList<>(candidates.size());
        for (Conclusion candidate : candidates) {
            Explain explain = explain(candidate, semantic, keywordRaw, entities, queryTermCount, now);
            double score = explain.score();
            if (score < settings.threshold()) {
                continue;
            }
            scored.add(new ScoredConclusion(candidate, score, explain));
        }

        // Ties break on id so that two conclusions with identical signals always come back in the same
        // order. Without it a fixture passes locally and fails wherever the rows arrive differently.
        scored.sort(Comparator.comparingDouble(ScoredConclusion::score).reversed().thenComparing(ScoredConclusion::id));
        return scored.size() <= limit ? scored : List.copyOf(scored.subList(0, limit));
    }

    public Explain explain(Conclusion candidate, Map<String, Double> semantic, Map<String, Double> keywordRaw,
            EntityBoost.Result entities, int queryTermCount, Instant now) {

        double sem = clamp(semantic.getOrDefault(candidate.id(), 0.0));
        double raw = keywordRaw.getOrDefault(candidate.id(), 0.0);
        // A raw score of exactly zero means no query term appears in the document at all, which is
        // an absent signal rather than a weak one. Passing it through the sigmoid would hand every
        // non-matching candidate a floor of about 0.03 — small, but paid uniformly, so it adds noise
        // to the ranking without ever distinguishing anything.
        double kw = raw <= 0.0 ? 0.0 : KeywordSignal.normalise(raw, queryTermCount);
        double ent = entities.boostFor(candidate.id());
        double reinf = ReinforcementSignal.of(candidate.timesDerived());
        double rec = RecencySignal.of(candidate.lastReinforcedAt(), now, settings.halfLifeDays());
        double lvl = candidate.level().rankWeight();

        return new Explain(sem, kw, ent, reinf, rec, lvl, settings.weights(), entities.namesFor(candidate.id()));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public RecallSettings settings() {
        return settings;
    }
}
