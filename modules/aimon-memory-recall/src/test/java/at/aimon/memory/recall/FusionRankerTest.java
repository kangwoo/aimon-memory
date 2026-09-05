package at.aimon.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.config.RecallSettings;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.core.model.SyncState;
import at.aimon.memory.recall.signal.EntityBoost;

/**
 * Fusion as a pure function.
 *
 * <p>Every existing test of this class reaches it through {@code RecallService} and a real database,
 * which proves the ranking end to end and says nothing about the three decisions the ranker owns on
 * its own: where the threshold is applied, how the limit truncates, and what happens when two
 * candidates score the same. The last is the one the class comment singles out — "without it a fixture
 * passes locally and fails wherever the rows arrive differently" — and it is precisely the kind of
 * thing a database-backed test cannot pin, because it cannot choose the order the rows arrive in.
 *
 * <p>{@code now} equals every candidate's {@code lastReinforcedAt}, so {@code rec} is exactly 1.0 and
 * the arithmetic below is stable rather than merely reproducible-today.
 */
class FusionRankerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final PairKey PAIR = new PairKey("ws", "alice", "bob");

    private static final FusionRanker RANKER = new FusionRanker(RecallSettings.DEFAULT);

    private static Conclusion conclusion(String id) {
        return conclusion(id, ConclusionLevel.EXPLICIT, 1);
    }

    private static Conclusion conclusion(String id, ConclusionLevel level, int timesDerived) {
        return new Conclusion(id, PAIR, "s1", "content of " + id, "content of " + id, "content of " + id, "hash-" + id,
                level, null, List.of(), List.of(), timesDerived, NOW, NOW, NOW, null, null, SyncState.SYNCED);
    }

    private static List<String> ids(List<ScoredConclusion> hits) {
        return hits.stream().map(ScoredConclusion::id).toList();
    }

    // ── the score itself ────────────────────────────────────────────────────

    /**
     * One fused score, to six decimal places and past them.
     *
     * <p>{@code 0.50·1.0 + 0.22·0 + 0.13·0 + 0.08·reinf(1) + 0.05·1.0 + 0.02·1.0}, where
     * {@code reinf(1) = 1 − 1/(1 + ln 2)}. The literal is the point: it is what "the denominator is
     * constant" means in practice, and any rescaling by how many signals answered would move it.
     */
    @Test
    void aFullyPinnedScore() {
        Conclusion only = conclusion("a");

        List<ScoredConclusion> ranked = RANKER.rank(List.of(only), Map.of("a", 1.0), Map.of(),
                EntityBoost.Result.empty(), 3, NOW, 10);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isCloseTo(0.6027507112680288, within(1e-12));
    }

    /**
     * A signal nobody produced contributes zero rather than shrinking the denominator.
     *
     * <p>Adding the keyword signal moves the score by exactly {@code weights.kw() × kw} and leaves
     * every other term alone. The formula this is derived from rescaled by how many stores answered,
     * so the same conclusion scored differently depending on configuration; the arithmetic below is
     * what makes a fixed threshold mean the same thing in two deployments.
     */
    @Test
    void aMissingSignalContributesZeroWithoutRescaling() {
        Conclusion candidate = conclusion("a");

        double semanticOnly = RANKER
                .rank(List.of(candidate), Map.of("a", 1.0), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10).get(0)
                .score();
        var withKeyword = RANKER
                .rank(List.of(candidate), Map.of("a", 1.0), Map.of("a", 9.0), EntityBoost.Result.empty(), 3, NOW, 10)
                .get(0);

        double kw = withKeyword.explain().kw();
        assertThat(kw).isGreaterThan(0.0);
        assertThat(withKeyword.score()).isCloseTo(semanticOnly + RecallSettings.DEFAULT.weights().kw() * kw,
                within(1e-12));
    }

    // ── the threshold ───────────────────────────────────────────────────────

    /**
     * The cut is on the fused score, not on semantic similarity.
     *
     * <p>This is the correction the class exists for. A conclusion a keyword query matched exactly but
     * that the vector search never saw has {@code sem = 0}; cutting on semantic alone would discard it
     * before fusion, which is the one case where the keyword signal is doing its job. Both candidates
     * here have {@code sem = 0} and only one has a keyword match — so a semantic-only threshold would
     * drop both.
     */
    @Test
    void theThresholdAppliesToTheFusedScoreNotToSemanticAlone() {
        Conclusion matched = conclusion("a");
        Conclusion unmatched = conclusion("b");
        FusionRanker ranker = new FusionRanker(RecallSettings.DEFAULT.withThreshold(0.20));

        List<ScoredConclusion> ranked = ranker.rank(List.of(matched, unmatched), Map.of(), Map.of("a", 12.0),
                EntityBoost.Result.empty(), 3, NOW, 10);

        assertThat(ids(ranked)).containsExactly("a");
    }

    /** A threshold above anything achievable empties the result rather than returning a floor. */
    @Test
    void aThresholdNothingClearsReturnsNothing() {
        FusionRanker ranker = new FusionRanker(RecallSettings.DEFAULT.withThreshold(1.01));

        assertThat(ranker.rank(List.of(conclusion("a")), Map.of("a", 1.0), Map.of(), EntityBoost.Result.empty(), 3, NOW,
                10)).isEmpty();
    }

    /** The comparison is {@code score < threshold}, so a candidate exactly on it is kept. */
    @Test
    void aScoreExactlyOnTheThresholdIsKept() {
        Conclusion candidate = conclusion("a");
        double exact = RANKER
                .rank(List.of(candidate), Map.of("a", 1.0), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10).get(0)
                .score();

        FusionRanker onTheLine = new FusionRanker(RecallSettings.DEFAULT.withThreshold(exact));
        assertThat(
                onTheLine.rank(List.of(candidate), Map.of("a", 1.0), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10))
                .hasSize(1);

        FusionRanker justAbove = new FusionRanker(RecallSettings.DEFAULT.withThreshold(Math.nextUp(exact)));
        assertThat(
                justAbove.rank(List.of(candidate), Map.of("a", 1.0), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10))
                .isEmpty();
    }

    // ── ordering and the limit ──────────────────────────────────────────────

    @Test
    void hitsComeBackByDescendingScore() {
        List<Conclusion> candidates = List.of(conclusion("low"), conclusion("high"), conclusion("mid"));
        Map<String, Double> semantic = Map.of("low", 0.10, "mid", 0.50, "high", 0.90);

        List<ScoredConclusion> ranked = RANKER.rank(candidates, semantic, Map.of(), EntityBoost.Result.empty(), 3, NOW,
                10);

        assertThat(ids(ranked)).containsExactly("high", "mid", "low");
    }

    /**
     * Identical signals order on id, whichever order the candidates arrived in.
     *
     * <p>The failure this prevents is the one the class comment describes: a ranking whose order comes
     * from the order the database happened to return rows in passes on the machine the fixture was
     * recorded on and fails everywhere else. Feeding the same two candidates both ways round is the
     * only way to say that, and it is not something a query-backed test can arrange.
     */
    @Test
    void identicalScoresBreakOnIdInBothInputOrders() {
        Conclusion first = conclusion("aaa");
        Conclusion second = conclusion("bbb");
        Map<String, Double> tied = Map.of("aaa", 0.5, "bbb", 0.5);

        var forwards = RANKER.rank(List.of(first, second), tied, Map.of(), EntityBoost.Result.empty(), 3, NOW, 10);
        var backwards = RANKER.rank(List.of(second, first), tied, Map.of(), EntityBoost.Result.empty(), 3, NOW, 10);

        assertThat(forwards.get(0).score()).isEqualTo(backwards.get(0).score());
        assertThat(ids(forwards)).containsExactly("aaa", "bbb");
        assertThat(ids(backwards)).containsExactly("aaa", "bbb");
    }

    /** The tie-break is on id alone, so it survives candidates that differ in everything else. */
    @Test
    void theTieBreakIgnoresEverythingButTheId() {
        // Same level and same times-derived, so the two score identically; only the ids differ.
        Conclusion zulu = conclusion("zulu", ConclusionLevel.DEDUCTIVE, 4);
        Conclusion alfa = conclusion("alfa", ConclusionLevel.DEDUCTIVE, 4);

        var ranked = RANKER.rank(List.of(zulu, alfa), Map.of(), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10);

        assertThat(ranked.get(0).score()).isEqualTo(ranked.get(1).score());
        assertThat(ids(ranked)).containsExactly("alfa", "zulu");
    }

    /** Truncation keeps the top of the ranking, not the head of the input. */
    @Test
    void theLimitTruncatesAfterSorting() {
        List<Conclusion> candidates = List.of(conclusion("worst"), conclusion("best"), conclusion("middle"));
        Map<String, Double> semantic = Map.of("worst", 0.1, "middle", 0.5, "best", 0.9);

        List<ScoredConclusion> ranked = RANKER.rank(candidates, semantic, Map.of(), EntityBoost.Result.empty(), 3, NOW,
                2);

        assertThat(ids(ranked)).containsExactly("best", "middle");
    }

    /** A limit at or above the number of survivors returns them all — the other branch of the same line. */
    @Test
    void aLimitLargerThanTheResultReturnsEverything() {
        List<Conclusion> candidates = List.of(conclusion("a"), conclusion("b"));

        assertThat(RANKER.rank(candidates, Map.of(), Map.of(), EntityBoost.Result.empty(), 3, NOW, 2)).hasSize(2);
        assertThat(RANKER.rank(candidates, Map.of(), Map.of(), EntityBoost.Result.empty(), 3, NOW, 99)).hasSize(2);
    }

    @Test
    void noCandidatesIsAnEmptyRankingRatherThanAFailure() {
        assertThat(RANKER.rank(List.of(), Map.of(), Map.of(), EntityBoost.Result.empty(), 3, NOW, 10)).isEmpty();
    }

    // ── explain ─────────────────────────────────────────────────────────────

    /**
     * A raw BM25 of exactly zero is an absent signal, not a weak one.
     *
     * <p>Passing it through the sigmoid would hand every non-matching candidate a floor of about 0.03,
     * paid uniformly and therefore distinguishing nothing — noise with a cost.
     */
    @Test
    void aZeroKeywordScoreStaysZeroRatherThanTakingTheSigmoidFloor() {
        Conclusion candidate = conclusion("a");

        var absent = RANKER.explain(candidate, Map.of(), Map.of(), EntityBoost.Result.empty(), 3, NOW);
        var explicitZero = RANKER.explain(candidate, Map.of(), Map.of("a", 0.0), EntityBoost.Result.empty(), 3, NOW);

        assertThat(absent.kw()).isZero();
        assertThat(explicitZero.kw()).isZero();
    }

    /** A semantic score outside [0,1] — a distance conversion gone wrong — is clamped, not propagated. */
    @Test
    void semanticSimilarityIsClampedIntoTheUnitInterval() {
        Conclusion candidate = conclusion("a");

        assertThat(RANKER.explain(candidate, Map.of("a", 1.8), Map.of(), EntityBoost.Result.empty(), 3, NOW).sem())
                .isEqualTo(1.0);
        assertThat(RANKER.explain(candidate, Map.of("a", -0.4), Map.of(), EntityBoost.Result.empty(), 3, NOW).sem())
                .isZero();
    }

    /** Explain carries the entity names that produced the boost, so the payload can be read back. */
    @Test
    void explainReportsTheEntitiesBehindTheBoost() {
        Conclusion candidate = conclusion("a");
        var boosts = new EntityBoost.Result(Map.of("a", 0.75), Map.of("a", List.of("서울")));

        var explain = RANKER.explain(candidate, Map.of(), Map.of(), boosts, 3, NOW);

        assertThat(explain.ent()).isEqualTo(0.75);
        assertThat(explain.matchedEntities()).containsExactly("서울");
    }

    @Test
    void theLevelSignalIsTheLevelsOwnRankWeight() {
        for (ConclusionLevel level : ConclusionLevel.values()) {
            var explain = RANKER.explain(conclusion("a", level, 1), Map.of(), Map.of(), EntityBoost.Result.empty(), 3,
                    NOW);
            assertThat(explain.lvl()).isEqualTo(level.rankWeight());
        }
    }

    @Test
    void theSettingsAreHandedBackUnchanged() {
        assertThat(RANKER.settings()).isEqualTo(RecallSettings.DEFAULT);
    }
}
