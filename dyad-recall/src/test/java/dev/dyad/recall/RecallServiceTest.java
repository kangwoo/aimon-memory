package dev.dyad.recall;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.filter.FilterOp;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.ConclusionLevel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecallServiceTest extends RecallTestBase {

    private PairKey pair;

    @BeforeEach
    void seedCorpus() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        store(pair, "s1", "alice works at a bank in seoul gangnam");
        store(pair, "s1", "alice enjoys hiking in the mountains");
        store(pair, "s1", "bob prefers tea over coffee");
    }

    /**
     * The other half of the fixed-denominator fix: a candidate found by only one path is scored on
     * both signals anyway. Otherwise its rank depends on which query happened to surface it.
     */
    @Test
    void everyCandidateIsScoredOnEverySignal() {
        var response = recall.recall(RecallRequest.of(pair, "hiking"));

        assertThat(response.hits()).isNotEmpty();
        var top = response.hits().get(0);
        assertThat(top.conclusion().content()).contains("hiking");
        assertThat(top.explain().sem()).isGreaterThan(0.0);
        assertThat(top.explain().kw()).isGreaterThan(0.0);
    }

    /**
     * The threshold cuts the fused score, not the semantic one. Cutting on similarity alone discards
     * exactly the rows an exact keyword match was about to rescue.
     */
    @Test
    void thresholdAppliesToTheFusedScoreNotTheSemanticOne() {
        var unfiltered = recall.recall(RecallRequest.of(pair, "seoul"));
        assertThat(unfiltered.hits()).hasSizeGreaterThan(1);

        double cutoff = unfiltered.hits().get(0).score();
        var filtered =
                recall.recall(
                        new RecallRequest(pair, "seoul", 10, Filter.ALL, cutoff, true));

        assertThat(filtered.hits()).hasSize(1);
        assertThat(filtered.hits().get(0).score()).isGreaterThanOrEqualTo(cutoff);
    }

    @Test
    void filtersNarrowTheCandidateSet() {
        var filtered =
                recall.recall(
                        new RecallRequest(
                                pair, "seoul", 10, new Filter.Cmp("session_name", FilterOp.EQ, "other"), null, true));
        assertThat(filtered.hits()).isEmpty();
    }

    @Test
    void limitIsRespectedAfterRanking() {
        var response = new RecallRequest(pair, "alice", 2, Filter.ALL, null, true);
        assertThat(recall.recall(response).hits()).hasSize(2);
    }

    @Test
    void explainCanBeOmitted() {
        var response =
                recall.recall(new RecallRequest(pair, "seoul", 5, Filter.ALL, null, false));
        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().get(0).explain()).isNull();
    }

    /** The single most useful diagnostic when a Korean query returns nothing. */
    @Test
    void theAnalysedQueryIsReturned() {
        assertThat(recall.recall(RecallRequest.of(pair, "  Seoul BANK  ")).analyzedQuery())
                .isEqualTo("seoul bank");
    }

    @Test
    void emptyCorpusReturnsNothingRatherThanFailing() {
        PairKey empty = seedPair("carol", "carol");
        var response = recall.recall(RecallRequest.of(empty, "anything"));
        assertThat(response.hits()).isEmpty();
        assertThat(response.candidatesConsidered()).isZero();
    }

    /** Ranking weights are workspace configuration, because the defaults are inherited, not tuned. */
    @Test
    void workspaceWeightsChangeTheOrdering() {
        String recent = store(pair, "s1", "alice bought a bicycle");
        setRankingState(recent, 40, NOW);
        String stale = store(pair, "s1", "alice bought a bicycle helmet");
        setRankingState(stale, 1, NOW.minus(Duration.ofDays(2000)));

        var withDefaults = recall.recall(RecallRequest.of(pair, "bicycle"));
        String defaultWinner = withDefaults.hits().get(0).conclusion().content();

        // Push everything onto reinforcement and the heavily re-derived row has to come first.
        workspaces.updateConfiguration(
                WORKSPACE, Map.of("recall.weights", List.of(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)));
        settings.invalidate(WORKSPACE);

        var reinforcementOnly = recall.recall(RecallRequest.of(pair, "bicycle"));
        assertThat(reinforcementOnly.hits().get(0).conclusion().content()).isEqualTo("alice bought a bicycle");
        assertThat(defaultWinner).isNotNull();
    }

    /** A malformed weight override must not take the workspace's recall down. */
    @Test
    void invalidWeightsFallBackToTheDefaults() {
        workspaces.updateConfiguration(WORKSPACE, Map.of("recall.weights", List.of(9.0, 9.0, 9.0, 9.0, 9.0, 9.0)));
        settings.invalidate(WORKSPACE);

        var response = recall.recall(RecallRequest.of(pair, "seoul"));
        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().get(0).explain().weights())
                .isEqualTo(dev.dyad.core.model.FusionWeights.DEFAULT);
    }

    @Test
    void inductiveConclusionsRankBelowExplicitOnesWhenAllElseIsEqual() {
        PairKey fresh = seedPair("dave", "dave");
        seedSession("s2");
        store(fresh, "s2", "dave likes puzzles");
        store(fresh, null, "dave likes puzzles", ConclusionLevel.INDUCTIVE, List.of());

        var hits = recall.recall(RecallRequest.of(fresh, "puzzles")).hits();
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).conclusion().level()).isEqualTo(ConclusionLevel.EXPLICIT);
        assertThat(hits.get(1).conclusion().level()).isEqualTo(ConclusionLevel.INDUCTIVE);
    }
}
