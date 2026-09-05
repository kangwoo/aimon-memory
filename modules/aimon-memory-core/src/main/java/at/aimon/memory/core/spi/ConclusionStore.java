package at.aimon.memory.core.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.CorpusStats;
import at.aimon.memory.core.model.DedupOutcome;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.model.Page;
import at.aimon.memory.core.model.ScoredConclusion;

/**
 * Conclusion persistence and retrieval.
 *
 * <p>The two retrieval methods return single-signal scores, not fused ones — fusion belongs to the
 * recall layer, which needs the candidates from both paths before it can combine anything. Both
 * methods are pair-scoped by construction: there is no overload that omits the pair.
 *
 * <h2>What is on this interface, and what is not</h2>
 *
 * <p>This used to declare five methods while every caller in the build injected
 * {@code ConclusionRepository} directly. An SPI nobody consumes is not an abstraction, it is a
 * second description of one class that drifts from it; the claim it underwrote — that another
 * backend could be substituted — was false, and the number of methods was the reason it looked
 * plausible.
 *
 * <p>The rule now is one line: <b>a method is here exactly when it is called from outside
 * {@code aimon-memory-store}.</b> That is the definition of a seam, and it is checkable — see
 * {@code SpiSurfaceTest}. Three things are deliberately left on the concrete class:
 *
 * <ul>
 * <li>{@code archiveCandidates} and {@code restore}, reached only from that module's own tests. A
 * test asserting how this implementation behaves is not a requirement on the next one.</li>
 * <li>{@code premisesOf}, which nothing calls at all. Widening an interface with dead code obliges
 * every future backend to implement it.</li>
 * </ul>
 */
public interface ConclusionStore {

    /** Vector neighbours, scored by cosine similarity in [0,1]. */
    List<ScoredConclusion> semantic(PairKey pair, float[] q, int limit, Filter f);

    /** Keyword matches over {@code content_analyzed}, scored by raw BM25 (unbounded, not [0,1]). */
    List<ScoredConclusion> keyword(PairKey pair, String analyzed, int limit, Filter f);

    /**
     * Document frequencies for the query's terms, over this pair's conclusions.
     *
     * <p>Part of the contract because BM25 is computed in {@code aimon-memory-text} rather than in
     * the database — see {@code Bm25} for why — so a backend owes the counts even though it does not
     * do the scoring.
     */
    CorpusStats corpusStats(PairKey pair, List<String> terms);

    /** Cosine similarity for an already-chosen set of ids, for explaining a ranking after the fact. */
    Map<String, Double> semanticScores(PairKey pair, float[] q, List<String> ids);

    /** Insert, reinforce or replace, per the three-stage dedup. Emits the matching audit event. */
    DedupOutcome upsert(ConclusionDraft draft);

    Optional<Conclusion> find(String workspaceName, String conclusionId);

    List<Conclusion> byIds(String workspaceName, List<String> conclusionIds);

    Page<Conclusion> list(PairKey pair, Filter filter, int page, int size);

    int countByLevel(PairKey pair, ConclusionLevel level);

    /** Everything held for one pair, newest first. The dreamer's and the peer card's input. */
    List<Conclusion> allForPair(PairKey pair, int limit);

    /** The conclusions that name this one as a premise — the forward edge of the reasoning tree. */
    List<Conclusion> derivedFrom(String workspace, String conclusionId);

    /** Tombstone rather than delete, so the audit trail survives the row. */
    void softDelete(String workspace, String conclusionId, Actor actor, Map<String, Object> detail, EventType event);

    void updateEmbedding(String conclusionId, float[] embedding);

    /** Records that a vector could not be produced, so the reconciler stops retrying in a loop. */
    void markSyncFailed(String workspace, String conclusionId, String reason);

    /** Rows written before an embedder was reachable; the reconciler's backfill queue. */
    List<Conclusion> pendingEmbedding(int limit);

    /** Rows past their retention horizon, for the forgetting pass. */
    List<Conclusion> expired(Instant now, int limit);
}
