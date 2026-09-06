package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.store.Drafts;
import at.aimon.memory.store.StoreTestBase;
import at.aimon.memory.store.TsQuery;
import at.aimon.memory.text.EnglishTextAnalyzer;

/**
 * Punctuation that survives analysis, all the way through a real Postgres.
 *
 * <p>{@code TsQueryTest} pins the string {@code TsQuery} produces. It cannot pin the two things that
 * actually matter — that the string parses, and that it matches the document the term was analysed
 * from — because both are properties of the server's tsquery grammar. Those are here.
 *
 * <p>The corpus is indexed with {@link EnglishTextAnalyzer} rather than the stub the other store
 * tests use. That is the whole point: the stub splits on every non-alphanumeric, so under it
 * {@code alice's} never exists as a token on either side and the bug this file covers is invisible.
 * The same analyzer produces the query terms, which is what production does.
 */
class KeywordPunctuationTest extends StoreTestBase {

    private final EnglishTextAnalyzer english = new EnglishTextAnalyzer();
    private PairKey pair;

    @BeforeEach
    void seedEnglishCorpus() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
    }

    private void store(String content) {
        conclusions.upsert(Drafts.explicit(pair, "s1", content, english));
    }

    /** Runs a term through the sanitiser and asks the server to parse the result. */
    private String parse(String tsquery) {
        return jdbc.sql("SELECT to_tsquery('simple', ?)::text").param(tsquery).query(String.class).single();
    }

    /**
     * A possessive matches the rows it was analysed from, which it did not before.
     *
     * <p>{@code alice's} is one token out of Lucene's standard tokenizer, so it lands in
     * {@code content_analyzed} and in the query terms alike. Stripped to {@code alices} it matched
     * nothing at all: {@code to_tsvector('simple', 'alice''s bank statement')} is
     * {@code 'alice':1 's':2 'bank':3 'statement':4}, and no lexeme in it is {@code alices}. Kept, it
     * parses to {@code 'alice' <-> 's'} and finds both rows. The 0-of-3 and 2-of-3 in this assertion
     * are the measurement, not an illustration — the stripped form is queried here too so that the
     * gap stays visible rather than being remembered.
     */
    @Test
    void aPossessiveMatchesTheRowsItWasAnalysedFrom() {
        store("alice's bank statement");
        store("alice's hiking notes");
        store("bob prefers tea");

        assertThat(conclusions.keyword(pair, english.analyze("alice's"), 10, Filter.ALL)).hasSize(2)
                .allSatisfy(hit -> assertThat(hit.conclusion().content()).contains("alice's"));

        // What the sanitiser used to send. Pinned so that reverting the fix cannot look like a pass.
        assertThat(conclusions.keyword(pair, "alices", 10, Filter.ALL)).isEmpty();
    }

    /**
     * A grouped number matches too, for the same reason and with a different one.
     *
     * <p>The comma needs no positional rule: it is not a tsquery operator anywhere, so it simply
     * joins the allowlist. {@code to_tsquery('simple', '50,000')} is {@code '50' <-> '000'} and
     * {@code to_tsvector('simple', 'deposit of 50,000 won')} puts {@code '50'} and {@code '000'} at
     * adjacent positions, so the phrase matches. The standard tokenizer keeps a comma only between
     * digits — {@code x,y} splits — so this is the only shape that reaches the builder.
     */
    @Test
    void aGroupedNumberMatchesTheRowsItWasAnalysedFrom() {
        store("deposit of 50,000 won");
        store("transfer 50,000 to savings");
        store("bob prefers tea");

        assertThat(conclusions.keyword(pair, english.analyze("50,000"), 10, Filter.ALL)).hasSize(2)
                .allSatisfy(hit -> assertThat(hit.conclusion().content()).contains("50,000"));

        assertThat(conclusions.keyword(pair, "50000", 10, Filter.ALL)).isEmpty();
    }

    /**
     * The colon stays stripped, and this is what that costs and why it is still right.
     *
     * <p>A word-internal colon survives standard analysis intact, so {@code note:draft} reaches the
     * builder as one token exactly like the two above — but it cannot be kept the same way. It is a
     * real operator, and the failure is the statement rather than the ranking: the second assertion
     * runs the unstripped term against the server and gets {@code syntax error in tsquery}, which is
     * a failed recall for every term in the query, not a missing row. The positional rule that saves
     * the apostrophe does not apply, because the colon already has an alphanumeric on both sides.
     *
     * <p>So the cost is real and accepted: the row that contains the token is not returned. Quoting
     * the operand would recover it — {@code to_tsquery('simple', '''note:draft''')} is
     * {@code 'note' <-> 'draft'} and does match — and that is a change to how every operand is
     * emitted, which is why it is not made here.
     */
    @Test
    void aWordInternalColonIsStrippedBecauseKeepingItWouldFailTheStatement() {
        store("note:draft about seoul");
        store("bob prefers tea");

        assertThat(english.tokens("note:draft")).containsExactly("note:draft");
        assertThat(conclusions.keyword(pair, english.analyze("note:draft"), 10, Filter.ALL)).isEmpty();

        assertThatThrownBy(() -> parse("note:draft")).rootCause().hasMessageContaining("syntax error in tsquery");
        // And the recovery that is deliberately not taken, so the deferral stays checkable.
        assertThat(parse("'note:draft'")).isEqualTo("'note' <-> 'draft'");
    }

    /**
     * No sanitised term can open a quoted lexeme, which is the one way a kept apostrophe could break
     * a recall outright.
     *
     * <p>An unterminated quote is not a worse match, it is {@code syntax error in tsquery} for the
     * whole statement — every other term in the query goes down with it. Three of the seven shapes
     * below are that failure exactly ({@code 'foo}, {@code ''}, {@code '}); the rest parse on their
     * own ({@code foo'} is {@code 'foo'}, {@code a''b} is {@code 'a' <-> 'b'}), because only an
     * operand that <em>opens</em> with a quote is read as a quoted lexeme. They are all here because
     * the property being pinned is about the output rather than the input: whatever {@code orOf}
     * returns has to parse, and the way it guarantees that is by never starting with a quote. The
     * last assertion runs the real failure against the server, so that is a measured hazard rather
     * than a theoretical one. None of the seven is a token an analyzer in this build hands back —
     * though one of the shapes is, which the test below is about. {@code orOf} is public, and the
     * condition is cheap.
     */
    @Test
    void noSanitisedTermCanOpenAQuotedLexeme() {
        for (String hostile : List.of("'foo", "foo'", "''", "'", "a''b", "'foo'", "a'")) {
            String built = TsQuery.orOf(List.of(hostile, "bank"));
            assertThatCode(() -> parse(built)).as("sanitised form of %s must parse: %s", hostile, built)
                    .doesNotThrowAnyException();
            assertThat(built).as("sanitised form of %s must not start with a quote", hostile).doesNotStartWith("'");
        }

        // The kept form still parses, so the condition is not simply deleting every quote.
        assertThat(parse(TsQuery.orOf(List.of("alice's", "bank")))).isEqualTo("'alice' <-> 's' | 'bank'");

        assertThatThrownBy(() -> parse("'foo | bank")).rootCause().hasMessageContaining("syntax error in tsquery");
    }

    /**
     * The two SQL paths now agree about which rows contain a possessive — and the residual gap
     * between them, which this change does not close, has a number on it.
     *
     * <p>{@code corpusStats} counts document frequency with {@code plainto_tsquery} on the raw term,
     * so it always understood {@code alice's} as {@code 'alice' & 's'} and found the rows. The
     * keyword path asked for {@code alices} and found none. Two SQL statements in one ranking
     * disagreeing about what a term means is the part this change fixes: both now find the two rows
     * that actually contain the token.
     *
     * <p>What is left is a different disagreement with a different cause. {@code plainto_tsquery}
     * builds {@code &} and {@code to_tsquery} builds {@code <->}, so a row holding both lexemes at
     * non-adjacent positions — {@code alice draws s curves} — counts toward the document frequency
     * without being a match. Here that is df 3 against 2 matching rows, on a corpus of 3. It inflates
     * IDF slightly and so understates the term's weight, in the same direction for every candidate.
     * Closing it means changing what {@code corpusStats} measures, which moves every BM25 score in
     * the system and belongs to its own change with its own golden update.
     */
    @Test
    void theKeywordPathAndCorpusStatsNowAgreeAboutAPossessive() {
        store("alice's bank statement");
        store("alice's hiking notes");
        // Holds the lexemes 'alice' and 's', never the token alice's.
        store("alice draws s curves");

        assertThat(conclusions.keyword(pair, english.analyze("alice's"), 10, Filter.ALL)).hasSize(2)
                .allSatisfy(hit -> assertThat(hit.conclusion().content()).contains("alice's"));

        var stats = conclusions.corpusStats(pair, List.of("alice's"));
        assertThat(stats.documentCount()).isEqualTo(3);
        assertThat(stats.documentFrequency().get("alice's")).isEqualTo(3L);
    }

    /**
     * A trailing quote does reach the builder, and dropping it costs no match — measured, because the
     * claim that it could not reach the builder is the one this change kept getting wrong.
     *
     * <p>Lucene's standard tokenizer implements UAX#29, whose WB7a keeps a quote that follows a
     * Hebrew letter — the geresh, which Hebrew writes foreign sounds with. So {@code ג'ורג'} is a
     * single token with the quote on the end, and "the analyzer only ever puts a quote between two
     * alphanumerics", written in {@code TsQuery}, in {@code TsQueryTest} and in this change's own
     * commit message, was false. The positional guard drops that quote, exactly as designed.
     *
     * <p>What that costs is nothing, and the reason is worth having on record rather than assumed
     * from the shape. A quote at the end of an operand is a no-op to the tsquery parser, so the
     * sanitised term and the analyzer's own token parse to the same {@code 'ג' <-> 'ורג'} and match
     * the same rows. The guard needed no change; the sentence justifying it did.
     */
    @Test
    void aTrailingQuoteFromTheAnalyzerCostsNoMatchWhenDropped() {
        store("ג'ורג' וושינגטון");
        store("bob prefers tea");

        // The shape the guard was documented as never having to see.
        assertThat(english.tokens("ג'ורג'")).containsExactly("ג'ורג'");
        assertThat(TsQuery.orOf(List.of("ג'ורג'"))).isEqualTo("ג'ורג");

        // Dropped and unpaid for: the server reads both forms identically, and the row comes back.
        assertThat(parse("ג'ורג")).isEqualTo("'ג' <-> 'ורג'").isEqualTo(parse("ג'ורג'"));
        assertThat(conclusions.keyword(pair, english.analyze("ג'ורג'"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("ג'ורג'"));
    }

    /**
     * A row found through the keyword path carries a real BM25 score for the possessive term.
     *
     * <p>Worth asserting because the two halves of this ranking count terms differently. The SQL
     * decides candidacy through lexemes, but {@code Bm25.score} compares the raw query terms against
     * a whitespace split of {@code content_analyzed}, so its notion of the term is the analyzer's
     * token {@code alice's} — which is exactly what the query terms now are. Before the fix nothing
     * reached this code path through {@code keyword} at all, and a possessive row only ever got its
     * score from {@code RecallService.fillMissingKeyword} after the semantic path happened to surface
     * it. A zero here would mean the SQL and the arithmetic had been reunited on the candidate set
     * and not on the term.
     */
    @Test
    void aPossessiveCandidateIsScoredOnTheTermTheAnalyzerProduced() {
        store("alice's bank statement");
        store("bob prefers tea");

        assertThat(conclusions.keyword(pair, english.analyze("alice's"), 10, Filter.ALL))
                .allSatisfy(hit -> assertThat(hit.score()).isGreaterThan(0.0));
    }
}
