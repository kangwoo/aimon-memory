package dev.dyad.core.model;

import dev.dyad.core.DyadException;

/**
 * The six ranking weights.
 *
 * <p>They sum to exactly 1.00 and that invariant is enforced here rather than trusted. It is the
 * whole point of the formula: a missing signal contributes zero instead of shrinking the
 * denominator, so scores stay comparable across configurations and dropping a signal lowers a score
 * honestly without reordering the candidates around it.
 */
public record FusionWeights(double sem, double kw, double ent, double reinf, double rec, double lvl) {

    /** Starting point: mem0's semantic/keyword/entity split, with the three new signals fitted in. */
    public static final FusionWeights DEFAULT = new FusionWeights(0.50, 0.22, 0.13, 0.08, 0.05, 0.02);

    private static final double SUM_TOLERANCE = 1e-9;

    public FusionWeights {
        double sum = sem + kw + ent + reinf + rec + lvl;
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new DyadException(
                    "bad_weights", "fusion weights must sum to 1.00, got " + sum);
        }
        for (double w : new double[] {sem, kw, ent, reinf, rec, lvl}) {
            if (w < 0.0) {
                throw new DyadException("bad_weights", "fusion weights must be non-negative");
            }
        }
    }

    public double[] asArray() {
        return new double[] {sem, kw, ent, reinf, rec, lvl};
    }
}
