package at.aimon.memory.recall;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;

/**
 * A Tier 1 query.
 *
 * @param threshold overrides the workspace setting when non-null; applied to the fused score
 * @param includeExplain explain is cheap and on by default, because tuning without it is guesswork
 */
public record RecallRequest(PairKey pair, String query, int limit, Filter filter, Double threshold,
        boolean includeExplain) {

    public static final int DEFAULT_LIMIT = 10;

    /**
     * Ceiling on results, enforced here rather than only at the edge.
     *
     * <p>Each signal path oversamples by a multiple of the limit, so this bounds several queries at
     * once. Clamping in the record means every caller is covered — the HTTP layer, the dialectic
     * tools, and anything added later that forgets to.
     */
    public static final int MAX_LIMIT = 100;

    public RecallRequest {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (filter == null) {
            filter = Filter.ALL;
        }
    }

    public static RecallRequest of(PairKey pair, String query) {
        return new RecallRequest(pair, query, DEFAULT_LIMIT, Filter.ALL, null, true);
    }
}
