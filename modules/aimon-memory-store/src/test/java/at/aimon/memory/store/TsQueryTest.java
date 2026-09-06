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
 *
 * <p>These are the string shapes. That the shapes actually parse and actually match is a property of
 * a server rather than of this class, and lives in {@code KeywordPunctuationTest} against a real
 * Postgres.
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
     * <p>Six of these seven forms no analyzer emits, which was run rather than assumed — all three
     * analyzers in this build, over exactly these strings. The standard tokenizer breaks on
     * {@code &}, {@code |}, {@code !}, brackets and {@code *}, and it strips the quotes off
     * {@code 'j'}, which reaches here as {@code j} already; Nori and the bigram fallback break on all
     * of them. (Only off <em>this</em> shape — a quote is not always dropped by analysis, which
     * {@link #aQuoteWithoutAnAlphanumericOnBothSidesIsDropped} says more about.) {@code g:h} is the
     * exception — a word-internal colon survives standard analysis intact — which is why it gets a
     * test of its own.
     *
     * <p>The point is that a term can never contribute an operator to the assembled query. {@code &}
     * and {@code !} would change the query from a union into an intersection or a negation, which is
     * the failure that matters — not a syntax error, but a silently different set of rows.
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
     * The possessive and the grouped number keep their punctuation, which is the fix.
     *
     * <p>Lucene's standard tokenizer hands back {@code alice's} and {@code 50,000} as single tokens,
     * so each lands in {@code content_analyzed} and in the query terms alike. Stripping them to
     * {@code alices} and {@code 50000} made every English possessive, every contraction and every
     * grouped number contribute no keyword candidates at all — measured on Postgres 16 over a
     * three-row corpus, 0 rows where the unstripped form finds 2, for both. Kept now: the stripping
     * bought nothing, because neither character is a tsquery operator in this position.
     * {@code to_tsquery('simple', 'alice''s | bank')} is {@code 'alice' <-> 's' | 'bank'} and
     * {@code to_tsquery('simple', 'alice | 50,000')} is {@code 'alice' | '50' <-> '000'}, and the
     * document side splits identically, so the phrase matches the text it was analysed from.
     */
    @Test
    void possessivesAndGroupedNumbersKeepTheirPunctuation() {
        assertThat(TsQuery.orOf(List.of("alice's", "bank"))).isEqualTo("alice's | bank");
        assertThat(TsQuery.orOf(List.of("don't"))).isEqualTo("don't");
        assertThat(TsQuery.orOf(List.of("50,000", "won"))).isEqualTo("50,000 | won");
        // A comma is kept wherever it falls, so the two rules compose without a special case.
        assertThat(TsQuery.orOf(List.of("50,000's"))).isEqualTo("50,000's");
    }

    /**
     * A quote is kept only with an alphanumeric on both sides, and that condition is the safety
     * property rather than a tidiness one.
     *
     * <p>Bare, a quote opens a quoted lexeme that never closes, and the statement dies rather than
     * ranking badly: on Postgres 16 {@code to_tsquery('simple', '''foo')} is
     * {@code ERROR: syntax error in tsquery}, and so is {@code 'bank | ''foo | seoul'} — one bad term
     * takes the whole recall down, not just its own operand. Requiring an alphanumeric on each side
     * excludes every one of these, and because an alphanumeric is itself always kept, a surviving
     * quote is still flanked by one in the output: the result can never begin with a quote, end with
     * one, or hold two in a row.
     *
     * <p>No analyzer in this build hands back any of these five strings — all three reduce
     * {@code 'foo}, {@code foo'}, {@code ''} and {@code '} to a quote-free token or to nothing, and
     * {@code a''b} to {@code a} and {@code b}. One of the <em>shapes</em> is another matter: a quote
     * after a Hebrew letter survives standard analysis, so a token ending in one does reach
     * {@code orOf}, and the second assertion is the rule that handles it. Dropping that quote costs
     * no match, which is measured against the server in
     * {@code KeywordPunctuationTest.aTrailingQuoteFromTheAnalyzerCostsNoMatchWhenDropped} rather than
     * assumed here — "the analyzer never emits a quote outside a word" has been written in this
     * change three times and been wrong every time. What makes these five worth pinning is not that
     * they are unreachable but that {@code orOf} is public and the cost of being wrong is a failed
     * statement rather than a worse ranking.
     */
    @Test
    void aQuoteWithoutAnAlphanumericOnBothSidesIsDropped() {
        assertThat(TsQuery.orOf(List.of("'foo"))).isEqualTo("foo");
        assertThat(TsQuery.orOf(List.of("foo'"))).isEqualTo("foo");
        assertThat(TsQuery.orOf(List.of("a''b"))).isEqualTo("ab");
        assertThat(TsQuery.orOf(List.of("''"))).isEmpty();
        assertThat(TsQuery.orOf(List.of("'"))).isEmpty();
        // Word-internal is the one form that survives, including next to a non-Latin letter.
        assertThat(TsQuery.orOf(List.of("a'b", "한글's"))).isEqualTo("a'b | 한글's");
        // The neighbours are read on the term as it arrived, not on what has been appended so far:
        // the character between these quotes is dropped, so the quotes separate nothing and go too.
        assertThat(TsQuery.orOf(List.of("a'&'b"))).isEqualTo("ab");
        assertThat(TsQuery.orOf(List.of("a':b"))).isEqualTo("ab");
    }

    /**
     * The colon is the one of the three that stays stripped, and it costs a match to do it.
     *
     * <p>It is a real operator — weight and prefix — so a term carrying one is a failed statement
     * rather than a poor one: {@code to_tsquery('simple', 'g:h')} and
     * {@code to_tsquery('simple', 'a | g:h')} are both {@code ERROR: syntax error in tsquery} on
     * Postgres 16. The positional rule that rescues the apostrophe cannot rescue this: {@code g:h}
     * already has an alphanumeric on both sides. The only recovery is to quote the operand, and that
     * is a change to how every term is emitted rather than a character in an allowlist — measured, it
     * does work ({@code to_tsquery('simple', '''note:draft''')} is {@code 'note' <-> 'draft'} and
     * matches {@code to_tsvector('simple', 'note:draft here')}, where {@code notedraft} matches
     * nothing), so this is a deliberate deferral and not an impossibility. Pinned so the cost stays
     * on record.
     */
    @Test
    void aWordInternalColonIsStillStripped() {
        assertThat(TsQuery.orOf(List.of("note:draft"))).isEqualTo("notedraft");
        assertThat(TsQuery.orOf(List.of("alice's", "note:draft", "50,000"))).isEqualTo("alice's | notedraft | 50,000");
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
     *
     * <p>A comma-only term is no longer one of the dropped ones, which is the visible cost of
     * allowlisting the character unconditionally. It joins {@code -}, {@code .} and {@code _} as an
     * operand with no lexeme in it, and Postgres treats it the same way: {@code 'foo | ,,, | bar'} is
     * {@code 'foo' | 'bar'}, and {@code ','} alone is an empty tsquery with a NOTICE and no error. No
     * analyzer in this build emits such a token — all three drop a lone comma — so this is a shape
     * the sanitiser permits rather than one production produces.
     */
    @Test
    void termsThatSanitiseToNothingAreDropped() {
        assertThat(TsQuery.orOf(List.of("alice", "&&&", "bank"))).isEqualTo("alice | bank");
        assertThat(TsQuery.orOf(List.of("()", "!!"))).isEmpty();
        assertThat(TsQuery.orOf(List.of("alice", ",,,", "bank"))).isEqualTo("alice | ,,, | bank");
    }
}
