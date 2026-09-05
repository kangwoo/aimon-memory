package at.aimon.memory.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What reaches {@code to_tsquery}.
 *
 * <p>This builder sits between analyzer output and a Postgres parser that has opinions about
 * punctuation, and nothing tested it. The two things it promises are that terms are ORed — the whole
 * argument against {@code plainto_tsquery}, which ANDs and so turns a ranking problem into a filtering
 * one — and that no term can carry a tsquery operator into the statement.
 */
class TsQueryTest {

    @Test
    void termsAreOredSoOneMatchIsEnough() {
        assertThat(TsQuery.orOf(List.of("alice", "bank", "seoul"))).isEqualTo("alice | bank | seoul");
    }

    @Test
    void aSingleTermNeedsNoOperator() {
        assertThat(TsQuery.orOf(List.of("alice"))).isEqualTo("alice");
    }

    /** The caller checks for empty and skips the query entirely, so this is a contract, not a detail. */
    @Test
    void noTermsIsAnEmptyQuery() {
        assertThat(TsQuery.orOf(List.of())).isEmpty();
    }

    /**
     * Operator characters are stripped rather than escaped.
     *
     * <p>The input is analyzer output, so anything removed here was never a token; the point is that a
     * term can never contribute an operator to the assembled query. {@code &} and {@code !} would
     * change the query from a union into an intersection or a negation, which is the failure that
     * matters — not a syntax error, but a silently different set of rows.
     */
    @Test
    void operatorCharactersCannotSurviveIntoTheQuery() {
        assertThat(TsQuery.orOf(List.of("a&b", "c|d", "!e", "(f)", "g:h", "i*", "'j'")))
                .isEqualTo("ab | cd | e | f | gh | i | j");
    }

    /** Word-internal punctuation a real token can carry is kept. */
    @Test
    void underscoresHyphensAndDotsAreKept() {
        assertThat(TsQuery.orOf(List.of("snake_case", "co-op", "e.g"))).isEqualTo("snake_case | co-op | e.g");
    }

    @Test
    void nonLatinTermsSurviveIntact() {
        assertThat(TsQuery.orOf(List.of("서울", "강남"))).isEqualTo("서울 | 강남");
    }

    /**
     * Duplicates are dropped, so a repeated term does not appear twice in the query.
     *
     * <p>It would be harmless to {@code to_tsquery} and merely noisy in a log — but sanitising can
     * also *create* duplicates out of terms that differed only in stripped punctuation, which is the
     * case worth pinning.
     */
    @Test
    void duplicatesAreCollapsedIncludingOnesSanitisingCreated() {
        assertThat(TsQuery.orOf(List.of("alice", "alice"))).isEqualTo("alice");
        assertThat(TsQuery.orOf(List.of("alice", "alice!", "(alice)"))).isEqualTo("alice");
    }

    /**
     * A term with nothing left after sanitising is dropped rather than joined as an empty operand.
     *
     * <p>Verified against a real Postgres 16 that {@code to_tsquery('simple', '- | foo')} is not an
     * error — the parser finds no lexeme, says so in a NOTICE and returns {@code 'foo'} — so a term
     * that sanitises to punctuation is not a correctness problem either way. Dropping the empty ones
     * is still what keeps the assembled string readable in a log and free of dangling separators.
     */
    @Test
    void termsThatSanitiseToNothingAreDropped() {
        assertThat(TsQuery.orOf(List.of("alice", "&&&", "bank"))).isEqualTo("alice | bank");
        assertThat(TsQuery.orOf(List.of("()", "!!"))).isEmpty();
    }
}
