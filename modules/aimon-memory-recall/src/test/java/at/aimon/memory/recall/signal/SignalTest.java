package at.aimon.memory.recall.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.model.EntityMatch;
import at.aimon.memory.core.model.EntityRef;

class SignalTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    // ── kw ──────────────────────────────────────────────────────────────────

    /**
     * The midpoint rises with query length because BM25 raw scores do. Normalising against the maximum
     * in the result set would have been the obvious alternative, and it would make a document's score
     * depend on which other documents happened to be retrieved.
     */
    @Test
    void keywordCurveWidensWithQueryLength() {
        assertThat(KeywordSignal.curveFor(2).midpoint()).isEqualTo(5.0);
        assertThat(KeywordSignal.curveFor(5).midpoint()).isEqualTo(7.0);
        assertThat(KeywordSignal.curveFor(8).midpoint()).isEqualTo(9.0);
        assertThat(KeywordSignal.curveFor(12).midpoint()).isEqualTo(10.0);
        assertThat(KeywordSignal.curveFor(30).midpoint()).isEqualTo(12.0);
    }

    @Test
    void keywordSignalIsAMonotonicMapIntoTheUnitInterval() {
        assertThat(KeywordSignal.normalise(5.0, 2)).isCloseTo(0.5, within(1e-12));
        assertThat(KeywordSignal.normalise(0.0, 2)).isBetween(0.0, 0.05);
        assertThat(KeywordSignal.normalise(50.0, 2)).isBetween(0.95, 1.0);

        double previous = -1;
        for (double raw = 0; raw <= 30; raw += 0.5) {
            double value = KeywordSignal.normalise(raw, 5);
            assertThat(value).isGreaterThan(previous).isBetween(0.0, 1.0);
            previous = value;
        }
    }

    // ── reinf ───────────────────────────────────────────────────────────────

    /**
     * The signal neither source system has: one counts re-derivations without ranking on them, the
     * other ranks without the counter. Logarithmic, so the first repetition matters and the fortieth
     * does not, and bounded below 1 so it cannot outweigh semantic relevance.
     */
    @Test
    void reinforcementGrowsLogarithmicallyAndNeverReachesOne() {
        assertThat(ReinforcementSignal.of(1)).isCloseTo(1.0 - 1.0 / (1.0 + Math.log(2)), within(1e-12));
        assertThat(ReinforcementSignal.of(0)).isEqualTo(ReinforcementSignal.of(1));

        double first = ReinforcementSignal.of(2) - ReinforcementSignal.of(1);
        double later = ReinforcementSignal.of(41) - ReinforcementSignal.of(40);
        assertThat(first).isGreaterThan(later);
        assertThat(ReinforcementSignal.of(1_000_000)).isLessThan(1.0);
    }

    // ── rec ─────────────────────────────────────────────────────────────────

    /**
     * Measured from last reinforcement, not creation. That is what makes forgetting selective: a fact
     * that keeps coming up keeps resetting its own clock.
     */
    @Test
    void recencyHalvesEveryHalfLife() {
        assertThat(RecencySignal.of(NOW, NOW, 180)).isEqualTo(1.0);
        assertThat(RecencySignal.of(NOW.minus(Duration.ofDays(180)), NOW, 180)).isCloseTo(0.5, within(1e-9));
        assertThat(RecencySignal.of(NOW.minus(Duration.ofDays(360)), NOW, 180)).isCloseTo(0.25, within(1e-9));

        // A shorter half-life is a harsher forgetting curve, which is why it is workspace configuration.
        assertThat(RecencySignal.of(NOW.minus(Duration.ofDays(30)), NOW, 30))
                .isLessThan(RecencySignal.of(NOW.minus(Duration.ofDays(30)), NOW, 180));
    }

    @Test
    void recencyHandlesMissingAndFutureTimestamps() {
        assertThat(RecencySignal.of(null, NOW, 180)).isEqualTo(1.0);
        assertThat(RecencySignal.of(NOW.plus(Duration.ofDays(1)), NOW, 180)).isEqualTo(1.0);
        assertThat(RecencySignal.of(NOW.minus(Duration.ofDays(10)), NOW, 0)).isEqualTo(1.0);
    }

    // ── ent ─────────────────────────────────────────────────────────────────

    private static EntityMatch match(String id, String name, double similarity, int links) {
        return new EntityMatch(new EntityRef(id, "ws", name.toLowerCase(), name, null), similarity, links);
    }

    /** An entity attached to everything discriminates nothing — the entity layer's version of IDF. */
    @Test
    void countWeightDiscountsOverusedEntities() {
        assertThat(match("e", "서울", 1.0, 1).countWeight()).isEqualTo(1.0);
        assertThat(match("e", "서울", 1.0, 11).countWeight()).isCloseTo(1.0 / 1.1, within(1e-12));
        assertThat(match("e", "서울", 1.0, 501).countWeight()).isLessThan(0.005);
    }

    /** Without a floor, vector search's top-k means every query "matches" something. */
    @Test
    void lowSimilarityMatchesAreCutEntirely() {
        var result = EntityBoost.compute(List.of(match("e1", "서울", 0.49, 1)), List.of(new EntityBoost.Link("e1", "c1")),
                0.5);
        assertThat(result.byConclusion()).isEmpty();
        assertThat(result.boostFor("c1")).isZero();
    }

    /**
     * Max rather than sum. Two entities matching one conclusion is evidence it is relevant, not that
     * it is twice as relevant — summing would let a conclusion listing many places win on breadth.
     */
    @Test
    void multipleEntitiesTakeTheStrongestNotTheSum() {
        var result = EntityBoost.compute(List.of(match("e1", "서울", 0.9, 1), match("e2", "강남", 0.7, 1)),
                List.of(new EntityBoost.Link("e1", "c1"), new EntityBoost.Link("e2", "c1")), 0.5);

        assertThat(result.boostFor("c1")).isCloseTo(0.9, within(1e-12));
        assertThat(result.namesFor("c1")).containsExactly("강남", "서울");
    }

    @Test
    void unlinkedConclusionsGetNoBoost() {
        var result = EntityBoost.compute(List.of(match("e1", "서울", 0.9, 1)), List.of(new EntityBoost.Link("e1", "c1")),
                0.5);
        assertThat(result.boostFor("c2")).isZero();
        assertThat(result.namesFor("c2")).isEmpty();
        assertThat(EntityBoost.compute(List.of(), List.of(), 0.5).byConclusion()).isEmpty();
    }
}
