package at.aimon.memory.testkit.eval;

import java.util.List;
import java.util.Map;

/**
 * Information-retrieval metrics for the ranking evaluation set.
 *
 * <p>This is the second gate, and it answers a different question from the golden fixtures. Those
 * prove the formula is implemented as specified and that nobody changed it by accident; they cannot
 * say whether the weights are any good, because a wrong weight produces a consistent wrong answer
 * that a fixture happily records. Graded relevance judgements are the only thing that can.
 *
 * <p>Kept apart deliberately: a change that improves these numbers is expected to move the golden
 * fixtures, and a change that moves the fixtures without moving these is a bug.
 */
public final class RankingMetrics {

    private RankingMetrics() {
    }

    /**
     * Normalised discounted cumulative gain.
     *
     * <p>Graded, using the {@code 2^rel - 1} gain: the difference between a document that answers the
     * question and one that is merely on topic should not be linear, because to a reader it is not.
     * Normalised against the best achievable ordering, so a query with little relevant material does
     * not drag the mean down for being hard.
     */
    public static double ndcg(List<String> ranked, Map<String, Integer> judgements, int k) {
        double ideal = idealGain(judgements, k);
        if (ideal == 0.0) {
            return 0.0;
        }
        double gain = 0.0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            int relevance = judgements.getOrDefault(ranked.get(i), 0);
            gain += (Math.pow(2, relevance) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return gain / ideal;
    }

    private static double idealGain(Map<String, Integer> judgements, int k) {
        List<Integer> best = judgements.values().stream().sorted((a, b) -> b - a).limit(k).toList();
        double ideal = 0.0;
        for (int i = 0; i < best.size(); i++) {
            ideal += (Math.pow(2, best.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return ideal;
    }

    /**
     * Reciprocal rank of the first relevant result.
     *
     * <p>Complements nDCG rather than repeating it: nDCG rewards a good ordering overall, MRR asks
     * only whether the first thing shown is right — which for a memory system feeding a prompt is
     * often the whole question, because nothing further down will be read.
     */
    public static double reciprocalRank(List<String> ranked, Map<String, Integer> judgements) {
        for (int i = 0; i < ranked.size(); i++) {
            if (judgements.getOrDefault(ranked.get(i), 0) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Share of the relevant material that made it into the top k. */
    public static double recall(List<String> ranked, Map<String, Integer> judgements, int k) {
        long relevant = judgements.values().stream().filter(grade -> grade > 0).count();
        if (relevant == 0) {
            return 0.0;
        }
        long found = ranked.stream().limit(k).filter(id -> judgements.getOrDefault(id, 0) > 0).count();
        return (double) found / relevant;
    }

    /** Share of the top k that is relevant. Falls as a system pads results with near-misses. */
    public static double precision(List<String> ranked, Map<String, Integer> judgements, int k) {
        if (k == 0) {
            return 0.0;
        }
        long found = ranked.stream().limit(k).filter(id -> judgements.getOrDefault(id, 0) > 0).count();
        return (double) found / Math.min(k, Math.max(1, ranked.size()));
    }
}
