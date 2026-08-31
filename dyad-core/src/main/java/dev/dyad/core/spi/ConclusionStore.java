package dev.dyad.core.spi;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Conclusion;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.DedupOutcome;
import dev.dyad.core.model.ScoredConclusion;
import java.util.List;
import java.util.Optional;

/**
 * Conclusion persistence and retrieval.
 *
 * <p>The two retrieval methods return single-signal scores, not fused ones — fusion belongs to the
 * recall layer, which needs the candidates from both paths before it can combine anything. Both
 * methods are pair-scoped by construction: there is no overload that omits the pair.
 */
public interface ConclusionStore {

    /** Vector neighbours, scored by cosine similarity in [0,1]. */
    List<ScoredConclusion> semantic(PairKey pair, float[] q, int limit, Filter f);

    /** Keyword matches over {@code content_analyzed}, scored by raw BM25 (unbounded, not [0,1]). */
    List<ScoredConclusion> keyword(PairKey pair, String analyzed, int limit, Filter f);

    /** Insert, reinforce or replace, per the three-stage dedup. Emits the matching audit event. */
    DedupOutcome upsert(ConclusionDraft draft);

    Optional<Conclusion> find(String workspaceName, String conclusionId);

    List<Conclusion> byIds(String workspaceName, List<String> conclusionIds);
}
