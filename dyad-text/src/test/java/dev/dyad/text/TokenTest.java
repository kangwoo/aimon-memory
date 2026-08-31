package dev.dyad.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenTest {

    @Test
    void countsAndTruncatesOnTokenBoundaries() {
        String text = "Alice works at a bank in Seoul and enjoys long walks by the river.";
        assertThat(TokenCounter.count(text)).isGreaterThan(0);
        assertThat(TokenCounter.count("")).isZero();
        assertThat(TokenCounter.count(null)).isZero();

        String truncated = TokenCounter.truncate(text, 5);
        assertThat(TokenCounter.count(truncated)).isEqualTo(5);
        assertThat(text).startsWith(truncated);
    }

    @Test
    void truncationLeavesShortTextAlone() {
        assertThat(TokenCounter.truncate("short", 100)).isEqualTo("short");
        assertThat(TokenCounter.truncate("anything", 0)).isEmpty();
    }

    /**
     * Distinct tokens carry ten times the weight, which is what stops a padded restatement from
     * winning dedup against a shorter, more specific phrasing.
     */
    @Test
    void informationScoreRewardsDistinctTokensOverLength() {
        int repetitive = TokenSetScore.score(List.of("a", "a", "a", "a", "a", "a"), 10);
        int varied = TokenSetScore.score(List.of("a", "b", "c"), 10);
        assertThat(varied).isGreaterThan(repetitive);
        assertThat(TokenSetScore.score(List.of(), 10)).isZero();
    }
}
