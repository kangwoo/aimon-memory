package at.aimon.memory.recall.signal;

/**
 * Squashes a raw BM25 score into [0,1] with a logistic curve.
 *
 * <p>BM25 is unbounded and its magnitude scales with query length: a two-word query rarely clears 6,
 * a sixteen-word query routinely passes 15. Normalising by the maximum in the result set would make
 * a document's score depend on which other documents happened to be retrieved, which destroys the
 * comparability the fixed-weight formula exists to protect. A fixed curve per query-length band
 * keeps the mapping absolute.
 *
 * <p>The midpoint rises with query length and the steepness falls: longer queries produce higher raw
 * scores and need a gentler slope to keep discriminating near the middle of the range.
 */
public final class KeywordSignal {

    /** @param midpoint the raw score that maps to 0.5 */
    public record Curve(double midpoint, double steepness) {
    }

    private KeywordSignal() {
    }

    public static Curve curveFor(int queryTermCount) {
        if (queryTermCount <= 3) {
            return new Curve(5.0, 0.7);
        }
        if (queryTermCount <= 6) {
            return new Curve(7.0, 0.6);
        }
        if (queryTermCount <= 9) {
            return new Curve(9.0, 0.5);
        }
        if (queryTermCount <= 15) {
            return new Curve(10.0, 0.5);
        }
        return new Curve(12.0, 0.5);
    }

    public static double normalise(double rawBm25, int queryTermCount) {
        Curve curve = curveFor(queryTermCount);
        return 1.0 / (1.0 + Math.exp(-curve.steepness() * (rawBm25 - curve.midpoint())));
    }
}
