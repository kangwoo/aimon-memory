package dev.dyad.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.dyad.core.DyadException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FusionWeightsTest {

    /**
     * The invariant the whole formula rests on. If weights can sum to anything, a missing signal
     * rescales the score and the threshold stops meaning the same thing between deployments.
     */
    @Test
    void weightsMustSumToOne() {
        assertThat(FusionWeights.DEFAULT.sem() + FusionWeights.DEFAULT.kw() + FusionWeights.DEFAULT.ent()
                        + FusionWeights.DEFAULT.reinf() + FusionWeights.DEFAULT.rec() + FusionWeights.DEFAULT.lvl())
                .isCloseTo(1.0, within(1e-12));

        assertThatThrownBy(() -> new FusionWeights(0.5, 0.5, 0.5, 0.0, 0.0, 0.0))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("sum to 1.00");

        assertThatThrownBy(() -> new FusionWeights(1.2, -0.2, 0.0, 0.0, 0.0, 0.0))
                .isInstanceOf(DyadException.class);
    }

    /**
     * A missing signal lowers the score without reordering anything. This is the corrected behaviour:
     * a variable denominator would rescale both scores and could swap them.
     */
    @Test
    void missingSignalLowersScoreWithoutReorderingCandidates() {
        Explain strong =
                new Explain(0.90, 0.60, 0.0, 0.30, 0.90, 1.0, FusionWeights.DEFAULT, List.of());
        Explain weak =
                new Explain(0.70, 0.60, 0.0, 0.30, 0.90, 1.0, FusionWeights.DEFAULT, List.of());

        Explain strongWithEntity =
                new Explain(0.90, 0.60, 0.8, 0.30, 0.90, 1.0, FusionWeights.DEFAULT, List.of());
        Explain weakWithEntity =
                new Explain(0.70, 0.60, 0.8, 0.30, 0.90, 1.0, FusionWeights.DEFAULT, List.of());

        assertThat(strong.score()).isLessThan(strongWithEntity.score());
        assertThat(strong.score()).isGreaterThan(weak.score());
        assertThat(strongWithEntity.score()).isGreaterThan(weakWithEntity.score());

        // The entity signal shifted both by exactly its weighted contribution and nothing else.
        assertThat(strongWithEntity.score() - strong.score())
                .isCloseTo(FusionWeights.DEFAULT.ent() * 0.8, within(1e-12));
    }

    @Test
    void explainScoreIsRecomputedFromItsParts() {
        Explain explain =
                new Explain(0.8210, 0.6033, 0.45, 0.3010, 0.9048, 1.0, FusionWeights.DEFAULT, List.of("서울"));
        double expected =
                0.50 * 0.8210 + 0.22 * 0.6033 + 0.13 * 0.45 + 0.08 * 0.3010 + 0.05 * 0.9048 + 0.02 * 1.0;
        assertThat(explain.score()).isCloseTo(expected, within(1e-12));
    }
}
