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
     * <p>Six of these seven forms no analyzer emits, which was run rather than assumed: the standard
     * tokenizer breaks on {@code &}, {@code |}, {@code !}, brackets and {@code *}, and drops a quote
     * that is not between two alphanumerics, so {@code 'j'} reaches here as {@code j} already.
     * {@code g:h} is the exception — a word-internal colon survives analysis intact, so stripping it
     * does cost a match, which is what
     * {@link #punctuationThatSurvivesAnalysisIsStrippedAndLosesItsMatch} measures. It is stripped
     * anyway, because {@code :} is a real operator and a term keeping it is a syntax error. The
     * point is that a term can never contribute an operator to the assembled query. {@code &} and
     * {@code !} would change the query from a union into an intersection or a negation, which is the
     * failure that matters — not a syntax error, but a silently different set of rows.
     */
    @Test
    void operatorCharactersCannotSurviveIntoTheQuery() {
        assertThat(TsQuery.orOf(List.of("a&b", "c|d", "!e", "(f)", "g:h", "i*", "'j'")))
                .isEqualTo("ab | cd | e | f | gh | i | j");
    }

    /**
     * Word-internal punctuation a real token can carry is kept.
     *
     * <p>Kept knowing what {@code to_tsquery} then does with it, which is not obvious: measured on
     * Postgres 16, {@code co-op} parses to {@code 'co-op' <-> 'co' <-> 'op'} and {@code snake_case} to
     * {@code 'snake' <-> 'case'} — a phrase operator, an AND that also fixes the order, inside a
     * builder whose entire purpose is to avoid ANDing. It is still correct, because
     * {@code to_tsvector} splits the document the same way and the parts land at adjacent positions;
     * each of these matches a document containing it, and the {@code |} between terms is unaffected.
     * The reasoning and the numbers are on {@link TsQuery}. Pinned here so that a future sanitiser
     * that drops these characters has to argue with a failing test first.
     */
    @Test
    void underscoresHyphensAndDotsAreKept() {
        assertThat(TsQuery.orOf(List.of("snake_case", "co-op", "e.g"))).isEqualTo("snake_case | co-op | e.g");
    }

    /**
     * Punctuation that survives analysis is stripped here, and that is a measured recall gap rather
     * than a tidy detail.
     *
     * <p>Hyphens are safe because no analyzer in this build emits one. Three characters are not.
     * Lucene's standard tokenizer hands back {@code alice's}, {@code 50,000} and {@code note:draft}
     * as single tokens, so each lands in {@code content_analyzed} and in the query terms alike, and
     * each leaves here as {@code alices}, {@code 50000}, {@code notedraft}. On Postgres 16
     * {@code to_tsvector('simple', 'alice''s bank')} is {@code 'alice':1 's':2 'bank':3} and
     * {@code to_tsvector('simple', '50,000')} is {@code '50':1 '000':2}, so no stripped form matches
     * the document it was analysed from, while the unstripped one would: {@code alice's} parses to
     * {@code 'alice' <-> 's'}. Every English possessive and contraction, and every grouped number,
     * therefore contributes no candidates at all — over three rows, a query whose only term is
     * {@code alice's} finds 0 where the unstripped form finds 2, and {@code 50,000} measures the
     * same 0 against 2.
     *
     * <p>What usually saves the row is the other half of recall rather than this one.
     * {@code RecallService} always runs the semantic path, and {@code fillMissingKeyword} then scores
     * whatever that surfaced using the <em>unstripped</em> query terms — so once a row is in the
     * candidate set the possessive is scored correctly. {@code corpusStats} even counts its document
     * frequency correctly, because that path uses {@code plainto_tsquery} on the raw term and so
     * matches the very documents this one cannot return; the two SQL paths disagree about what
     * {@code alice's} means. The row is lost outright only when the semantic half misses it too, which
     * makes this a degraded signal in a hybrid ranking rather than a guaranteed miss — but "not a lost
     * row" would be too strong.
     *
     * <p>Not fixed here, and the three do not share a fix, which is the part worth writing down.
     * {@code note:draft} has to be stripped or quoted: {@code :} is a tsquery operator and
     * {@code to_tsquery('simple', 'note:draft')} is {@code syntax error in tsquery}. {@code alice's}
     * has to be stripped or kept only between two alphanumerics: a term that sanitises to a bare
     * {@code '} is a syntax error too — the failure this stripping exists to prevent — while a
     * word-internal one parses and matches. {@code 50,000} is neither. A comma is not an operator in
     * any position ({@code ', | foo'} is {@code 'foo'}, {@code 'alice | 50,000'} is
     * {@code 'alice' | '50' <-> '000'}), so it is the one of the three that could simply join the
     * allowlist, and it is here because a fix aimed at apostrophes alone would leave it broken.
     * Doing it properly means quoting each operand instead of filtering it. All of them change what a
     * whole class of query returns, so this wants its own change with its own Postgres test rather
     * than a character added to the allowlist in passing. Pinned so the behaviour is a decision on
     * record rather than a discovery.
     */
    @Test
    void punctuationThatSurvivesAnalysisIsStrippedAndLosesItsMatch() {
        assertThat(TsQuery.orOf(List.of("alice's", "bank"))).isEqualTo("alices | bank");
        assertThat(TsQuery.orOf(List.of("don't"))).isEqualTo("dont");
        assertThat(TsQuery.orOf(List.of("50,000", "note:draft"))).isEqualTo("50000 | notedraft");
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
     * error: the operand with no lexeme in it is dropped and the result is {@code 'foo'}, silently.
     * The "contains only stop words or doesn't contain lexemes" NOTICE that was claimed here fires
     * only when nothing at all survives — {@code to_tsquery('simple', '-')}, which returns an empty
     * tsquery. Either way a term that sanitises to punctuation is not a correctness problem, and
     * dropping the empty ones is what keeps the assembled string readable in a log and free of
     * dangling separators.
     */
    @Test
    void termsThatSanitiseToNothingAreDropped() {
        assertThat(TsQuery.orOf(List.of("alice", "&&&", "bank"))).isEqualTo("alice | bank");
        assertThat(TsQuery.orOf(List.of("()", "!!"))).isEmpty();
    }
}
