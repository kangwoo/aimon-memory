package at.aimon.memory.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What reaches {@code to_tsquery}.
 *
 * <p>This builder sits between analyzer output and a Postgres parser that has opinions about
 * punctuation. The two things it promises are that terms are ORed — the whole argument against
 * {@code plainto_tsquery}, which ANDs and so turns a ranking problem into a filtering one — and that
 * every term is emitted as a quoted operand with both of the tsquery lexer's two special characters
 * doubled, so that no term can carry an operator, an unterminated lexeme or a dangling escape into
 * the statement.
 *
 * <p>These are the string shapes. That the shapes actually parse and actually match is a property of
 * a server rather than of this class, and lives in {@code KeywordPunctuationTest} against a real
 * Postgres. The escaping is pinned on both sides on purpose: this tier runs with no Docker daemon,
 * and a missing escape is exactly the kind of thing that should fail fast.
 */
class TsQueryTest {

    @Test
    void termsAreOredSoOneMatchIsEnough() {
        assertThat(TsQuery.orOf(List.of("alice", "bank", "seoul"))).isEqualTo("'alice' | 'bank' | 'seoul'");
    }

    @Test
    void aSingleTermNeedsNoOperator() {
        assertThat(TsQuery.orOf(List.of("alice"))).isEqualTo("'alice'");
    }

    /** The caller checks for empty and skips the query entirely, so this is a contract, not a detail. */
    @Test
    void noTermsIsAnEmptyQuery() {
        assertThat(TsQuery.orOf(List.of())).isEmpty();
    }

    /**
     * Operator characters survive as literal text, which is the inversion this change is.
     *
     * <p>Every one of these seven used to be rewritten — {@code a&b} to {@code ab}, {@code !e} to
     * {@code e}, {@code g:h} to {@code gh} — and the rewrite is what cost the match, because the
     * document side keeps what the analyzer produced. Quoted, none of them is an operator: measured
     * on Postgres 16, all 33 non-alphanumeric printable ASCII characters as {@code a<c>b} parse
     * without error and match the document they were analysed from, where the stripped form matched
     * in 5 of the 33. The enumeration is {@code KeywordPunctuationTest.everyPrintableAsciiCharacter…}
     * against the server; what is pinned here is that this class stops deleting.
     */
    @Test
    void operatorCharactersSurviveAsLiteralText() {
        assertThat(TsQuery.orOf(List.of("a&b", "c|d", "!e", "(f)", "g:h", "i*", "'j'")))
                .isEqualTo("'a&b' | 'c|d' | '!e' | '(f)' | 'g:h' | 'i*' | '''j'''");
    }

    /**
     * Word-internal punctuation a real token can carry is untouched by quoting.
     *
     * <p>Kept knowing what {@code to_tsquery} then does with it, which is not obvious: measured on
     * Postgres 16, {@code 'co-op'} parses to {@code 'co-op' <-> 'co' <-> 'op'} and {@code 'snake_case'}
     * to {@code 'snake' <-> 'case'} — a phrase operator, an AND that also fixes the order, inside a
     * builder whose entire purpose is to avoid ANDing. It is still correct, because
     * {@code to_tsvector} splits the document the same way and the parts land at adjacent positions.
     * Quoting changes none of it: over all 1 606 strings of length ≤4 from {@code {a 1 _ - . , '}}
     * that the old sanitiser passed through unchanged, quoted and bare parse identically — 0
     * differences, 0 errors either way. The reasoning and the numbers are on {@link TsQuery}.
     */
    @Test
    void underscoresHyphensAndDotsAreKept() {
        assertThat(TsQuery.orOf(List.of("snake_case", "co-op", "e.g"))).isEqualTo("'snake_case' | 'co-op' | 'e.g'");
    }

    /**
     * The possessive and the grouped number keep their punctuation, as they did before this change.
     *
     * <p>Lucene's standard tokenizer hands back {@code alice's} and {@code 50,000} as single tokens,
     * so each lands in {@code content_analyzed} and in the query terms alike. Both survived the
     * allowlist and both survive quoting, and the quoted form is the same query the bare form was:
     * {@code 'alice''s'} and {@code alice's} both parse to {@code 'alice' <-> 's'}, measured. What
     * changes is the escape — the apostrophe is now doubled rather than passed through — and that is
     * what the second half of each assertion pins.
     *
     * <p>The curly apostrophe is the one that did not survive the allowlist, and it is the likelier
     * of the two in real text. {@code U+2019} is not a letter or a digit, so {@code don’t} was sent
     * as {@code dont} and matched nothing; it is not a tsquery operator either, so the operand does
     * not double it and the parser reads it as literal text. The last assertion pins that
     * distinction: the ASCII quote is escaped, the curly one is carried through untouched.
     */
    @Test
    void possessivesAndGroupedNumbersKeepTheirPunctuation() {
        assertThat(TsQuery.orOf(List.of("alice's", "bank"))).isEqualTo("'alice''s' | 'bank'");
        assertThat(TsQuery.orOf(List.of("don't"))).isEqualTo("'don''t'");
        assertThat(TsQuery.orOf(List.of("50,000", "won"))).isEqualTo("'50,000' | 'won'");
        assertThat(TsQuery.orOf(List.of("50,000's"))).isEqualTo("'50,000''s'");
        assertThat(TsQuery.orOf(List.of("don\u2019t"))).isEqualTo("'don\u2019t'");
    }

    /**
     * A quote is doubled wherever it falls, and its position no longer decides whether it survives.
     *
     * <p>The old rule kept a quote only between two alphanumerics, because bare it opened a quoted
     * lexeme that never closed and took the whole statement down. Inside an operand that hazard does
     * not exist: the lexeme is opened deliberately and every quote in the body is doubled, so the
     * only quotes the parser reads as syntax are the two this class put there. Measured on Postgres
     * 16, all five of these parse — {@code '''foo'} and {@code 'foo'''} are {@code 'foo'},
     * {@code 'a''''b'} is {@code 'a' <-> 'b'}, and {@code ''''} is an empty tsquery with a NOTICE and
     * no error — against 24 rejections out of 84 fuzz strings if the doubling is removed.
     *
     * <p>So the apostrophe is position-dependent in what this change does to it: already surviving
     * between two alphanumerics, newly surviving bare, leading, trailing and doubled.
     */
    @Test
    void aQuoteIsDoubledInEveryPosition() {
        assertThat(TsQuery.orOf(List.of("'foo"))).isEqualTo("'''foo'");
        assertThat(TsQuery.orOf(List.of("foo'"))).isEqualTo("'foo'''");
        assertThat(TsQuery.orOf(List.of("a''b"))).isEqualTo("'a''''b'");
        assertThat(TsQuery.orOf(List.of("''"))).isEqualTo("''''''");
        assertThat(TsQuery.orOf(List.of("'"))).isEqualTo("''''");
        assertThat(TsQuery.orOf(List.of("a'b", "한글's"))).isEqualTo("'a''b' | '한글''s'");
        // The geresh: one token out of the standard tokenizer, quote and all, and now kept whole.
        assertThat(TsQuery.orOf(List.of("ג'ורג'"))).isEqualTo("'ג''ורג'''");
    }

    /**
     * A backslash is doubled too, and it is the escape a reader is most likely to think unnecessary.
     *
     * <p>Inside a quoted lexeme {@code \} escapes whatever follows it, so a term ending in one eats
     * the closing quote and the statement dies: {@code 'foo\' | 'bank'} is
     * {@code ERROR: syntax error in tsquery} where {@code 'foo\\' | 'bank'} is
     * {@code 'foo' | 'bank'}. Measured over the same 84 strings, dropping this escape alone costs 38
     * rejections — more than dropping the quote escape does. The server side of it is
     * {@code KeywordPunctuationTest.aBackslashIsEscapedOrTheStatementDies}.
     */
    @Test
    void aBackslashIsDoubled() {
        assertThat(TsQuery.orOf(List.of("foo\\"))).isEqualTo("'foo\\\\'");
        assertThat(TsQuery.orOf(List.of("C:\\Users\\x"))).isEqualTo("'C:\\\\Users\\\\x'");
        assertThat(TsQuery.orOf(List.of("\\"))).isEqualTo("'\\\\'");
    }

    /**
     * The colon is no longer stripped, and that is the defect this change exists to fix.
     *
     * <p>{@code note:draft} is one token out of Lucene's standard tokenizer, so it lands in
     * {@code content_analyzed} and in the query terms alike. Bare it cannot be sent — a colon is the
     * weight and prefix operator, and {@code to_tsquery('simple','note:draft')} is
     * {@code ERROR: syntax error in tsquery} — so the allowlist deleted it and sent
     * {@code notedraft}, which matches nothing. Quoted it is literal text:
     * {@code to_tsquery('simple','''note:draft''')} is {@code 'note' <-> 'draft'} and does match the
     * document, which {@code KeywordPunctuationTest.aWordInternalColonMatchesItsOwnDocument} runs.
     */
    @Test
    void aWordInternalColonIsKept() {
        assertThat(TsQuery.orOf(List.of("note:draft"))).isEqualTo("'note:draft'");
        assertThat(TsQuery.orOf(List.of("alice's", "note:draft", "50,000")))
                .isEqualTo("'alice''s' | 'note:draft' | '50,000'");
    }

    /**
     * Combining marks and supplementary-plane code points reach the server intact.
     *
     * <p>The old loop walked {@code char} and kept {@code Character.isLetterOrDigit(c)}, which is
     * false for every combining mark and for both halves of a surrogate pair. {@code नमस्ते} was sent
     * as {@code नमसत} and {@code 𠀀} as the empty string — dropped from the query entirely — while
     * both analyzers emit each of them whole. Nothing here decides whether a term is worth sending;
     * that judgement is the server's, and {@code 😀} is why (see {@link TsQuery}).
     */
    @Test
    void combiningMarksAndSupplementaryCodePointsSurvive() {
        assertThat(TsQuery.orOf(List.of("नमस्ते"))).isEqualTo("'नमस्ते'");
        assertThat(TsQuery.orOf(List.of("𠀀"))).isEqualTo("'𠀀'");
        assertThat(TsQuery.orOf(List.of("😀"))).isEqualTo("'😀'");
    }

    @Test
    void nonLatinTermsSurviveIntact() {
        assertThat(TsQuery.orOf(List.of("서울", "강남"))).isEqualTo("'서울' | '강남'");
    }

    /**
     * Duplicates are dropped on the raw term, which is where they now come from.
     *
     * <p>Sanitising used to *create* duplicates out of terms that differed only in stripped
     * punctuation, so {@code ["alice","alice!","(alice)"]} collapsed to one operand. It no longer
     * does, and the three are sent as three: {@code to_tsquery} does not collapse duplicate operands
     * either (measured), so the server parses that to {@code 'alice' | 'alice' | 'alice'} — the same
     * rows, a longer string, and a {@code ts_rank_cd} unchanged by the repetition. Dropping a
     * genuinely repeated term is still worth doing, because the analyzer emits one per occurrence.
     */
    @Test
    void duplicatesAreCollapsedOnTheRawTerm() {
        assertThat(TsQuery.orOf(List.of("alice", "alice"))).isEqualTo("'alice'");
        assertThat(TsQuery.orOf(List.of("alice", "alice!", "(alice)"))).isEqualTo("'alice' | 'alice!' | '(alice)'");
    }

    /**
     * An empty term is skipped, because {@code ''} is the one operand that fails the statement.
     *
     * <p>Measured on Postgres 16: {@code to_tsquery('simple','''''')} is
     * {@code ERROR: syntax error in tsquery}, alone and inside an OR chain
     * ({@code 'alice' | '' | 'bank'} is the same error). Everything else this class can emit parses,
     * which is the invariant {@link TsQuery#orOf(List)} states — so the empty check is not tidiness,
     * it is the whole of the difference between a query and a 500.
     *
     * <p>A term that is all operators is no longer dropped: {@code ["()","!!"]} used to sanitise to
     * nothing and make {@code keyword()} return early. It is now sent, and the server drops it —
     * {@code '()' | '!!'} is an empty tsquery with a NOTICE and no error, so it matches nothing and
     * costs one round trip. Deciding that in Java would mean predicting which operands yield lexemes,
     * which is the claim {@link TsQuery} shows would be wrong.
     */
    @Test
    void anEmptyTermIsSkippedAndAnAllOperatorTermIsNot() {
        assertThat(TsQuery.orOf(List.of("alice", "", "bank"))).isEqualTo("'alice' | 'bank'");
        assertThat(TsQuery.operand("")).isEmpty();
        assertThat(TsQuery.operand(null)).isEmpty();
        assertThat(TsQuery.orOf(List.of("()", "!!"))).isEqualTo("'()' | '!!'");
        assertThat(TsQuery.orOf(List.of("alice", ",,,", "bank"))).isEqualTo("'alice' | ',,,' | 'bank'");
    }
}
