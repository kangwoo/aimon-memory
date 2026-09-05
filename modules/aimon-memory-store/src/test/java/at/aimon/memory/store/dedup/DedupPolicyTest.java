package at.aimon.memory.store.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.config.DedupSettings;

/**
 * The stage-3 decision, pinned without a database.
 *
 * <p>The class this exercises says it is "isolated from any SQL so it can be pinned by fixtures at its
 * boundaries", and until now nothing took it up on that: the rule was only ever reached through
 * {@code DedupTest}, which needs Postgres and therefore runs in the opt-in tier. Both halves of the
 * decision are arithmetic over two token lists, and the boundaries — the distance comparison and the
 * tie — are exactly where a silent change would stop the store deduplicating without failing anything.
 */
class DedupPolicyTest {

    private static final DedupPolicy DEFAULTS = new DedupPolicy(DedupSettings.DEFAULT);

    // ── the cosine gate ─────────────────────────────────────────────────────

    /**
     * The comparison is {@code <=}, so a candidate sitting exactly on the configured maximum is a
     * duplicate. Which side of the boundary is inclusive decides what happens to the most common case
     * there is — two encodings of the same sentence landing at precisely the threshold — and flipping
     * it silently turns a reinforcement into a second row.
     */
    @Test
    void theDistanceGateIncludesItsBoundary() {
        double max = DedupSettings.DEFAULT.cosineDistanceMax();

        assertThat(DEFAULTS.isNearDuplicate(max)).isTrue();
        assertThat(DEFAULTS.isNearDuplicate(Math.nextDown(max))).isTrue();
        assertThat(DEFAULTS.isNearDuplicate(Math.nextUp(max))).isFalse();
    }

    @Test
    void anIdenticalVectorIsNearAndAnOppositeOneIsNot() {
        assertThat(DEFAULTS.isNearDuplicate(0.0)).isTrue();
        assertThat(DEFAULTS.isNearDuplicate(1.0)).isFalse();
        assertThat(DEFAULTS.isNearDuplicate(2.0)).isFalse();
    }

    // ── which phrasing survives ─────────────────────────────────────────────

    /**
     * Ties go to the newcomer.
     *
     * <p>Stated in the class comment and load-bearing: it is what keeps the store converging on the
     * most recent wording of a fact that keeps being restated, instead of freezing the first one
     * forever. A {@code >} in place of the {@code >=} would leave the original in place every time and
     * nothing would fail — the row count is the same either way.
     */
    @Test
    void aTieGoesToTheNewcomer() {
        List<String> same = List.of("alice", "works", "at", "a", "bank");

        assertThat(DEFAULTS.newReplacesExisting(same, same)).isTrue();
        assertThat(DEFAULTS.newReplacesExisting(List.of("a", "b"), List.of("b", "a"))).isTrue();
    }

    /**
     * The weight is what makes this "information content" rather than length: three distinct tokens
     * beat five that say one thing over and over, even though the repetitive one is the longer text.
     */
    @Test
    void distinctTokensOutweighLength() {
        List<String> varied = List.of("gangnam", "seoul", "bank");
        List<String> repetitive = List.of("bank", "bank", "bank", "bank", "bank");

        assertThat(DEFAULTS.newReplacesExisting(varied, repetitive)).isTrue();
        assertThat(DEFAULTS.newReplacesExisting(repetitive, varied)).isFalse();
    }

    /**
     * What the weight actually buys, shown by removing it.
     *
     * <p>At {@code uniqueTokenWeight = 0} the score is pure length, and the padded restatement wins —
     * which is the outcome the weight exists to prevent. The same pair at the default weight goes the
     * other way. Pinned as a pair because the setting is workspace-tunable, and a value near zero
     * quietly restores the behaviour the design rejected.
     */
    @Test
    void withoutTheWeightTheLongerRestatementWins() {
        List<String> padded = List.of("bank", "bank", "bank", "bank", "bank");
        List<String> specific = List.of("gangnam", "seoul", "bank");

        DedupPolicy lengthOnly = new DedupPolicy(new DedupSettings(0.05, 0));
        assertThat(lengthOnly.newReplacesExisting(padded, specific)).isTrue();
        assertThat(DEFAULTS.newReplacesExisting(padded, specific)).isFalse();
    }

    /**
     * An empty token list scores zero, so anything at all replaces it and it replaces nothing.
     *
     * <p>Reachable: {@code contentAnalyzed} is analyzer output, and an analyzer that drops every token
     * of a short sentence — punctuation, a single stop word — hands this method an empty list.
     */
    @Test
    void anEmptyTokenListLosesToAnything() {
        assertThat(DEFAULTS.newReplacesExisting(List.of("anything"), List.of())).isTrue();
        assertThat(DEFAULTS.newReplacesExisting(List.of(), List.of("anything"))).isFalse();
        // Two empties are still a tie, and a tie is still the newcomer's.
        assertThat(DEFAULTS.newReplacesExisting(List.of(), List.of())).isTrue();
    }

    @Test
    void theSettingsAreHandedBackUnchanged() {
        assertThat(DEFAULTS.settings()).isEqualTo(DedupSettings.DEFAULT);
    }
}
