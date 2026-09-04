package at.aimon.memory.core.model;

import java.util.List;

/**
 * Why a conclusion scored what it scored.
 *
 * <p>Promoted to a first-class response field rather than a debug flag. It is the surface the golden
 * fixtures assert on, and the only way to see which signal produced an ordering when tuning.
 */
public record Explain(double sem, double kw, double ent, double reinf, double rec, double lvl, FusionWeights weights,
        List<String> matchedEntities) {

    public Explain {
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
    }

    /** The fused score. Deliberately recomputed from the parts so the parts can never drift. */
    public double score() {
        return weights.sem() * sem + weights.kw() * kw + weights.ent() * ent + weights.reinf() * reinf
                + weights.rec() * rec + weights.lvl() * lvl;
    }

    public double[] signals() {
        return new double[]{sem, kw, ent, reinf, rec, lvl};
    }
}
