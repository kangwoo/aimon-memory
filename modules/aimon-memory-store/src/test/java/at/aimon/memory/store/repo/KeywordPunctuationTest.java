package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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
 *
 * <p>Three of the tests below store nothing, because their subject is a character no analyzer in
 * this build emits inside a token: a stored corpus would be measuring the analyzer rather than the
 * operand. They ask the server the two questions directly — does this operand parse, and does it
 * match the text the token came from. Where a real analyzer does produce the token, as it does for
 * the colon and the geresh, the test goes through {@code conclusions.keyword} end to end.
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

    /** Asks the server to parse a tsquery string and hand back what it made of it. */
    private String parse(String tsquery) {
        return jdbc.sql("SELECT to_tsquery('simple', ?)::text").param(tsquery).query(String.class).single();
    }

    /** Does this tsquery match a document containing exactly this text? The whole property, in one row. */
    private boolean matches(String document, String tsquery) {
        return jdbc.sql("SELECT to_tsvector('simple', ?) @@ to_tsquery('simple', ?)").params(document, tsquery)
                .query(Boolean.class).single();
    }

    /**
     * A possessive matches the rows it was analysed from, which it did not before.
     *
     * <p>{@code alice's} is one token out of Lucene's standard tokenizer, so it lands in
     * {@code content_analyzed} and in the query terms alike. Stripped to {@code alices} it matched
     * nothing at all: {@code to_tsvector('simple', 'alice''s bank statement')} is
     * {@code 'alice':1 's':2 'bank':3 'statement':4}, and no lexeme in it is {@code alices}. Quoted,
     * it parses to {@code 'alice' <-> 's'} and finds both rows. The 0-of-3 and 2-of-3 in this
     * assertion are the measurement, not an illustration — the stripped form is queried here too so
     * that the gap stays visible rather than being remembered.
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
     * A grouped number matches too, and quoting leaves it exactly as it was.
     *
     * <p>The comma was already allowlisted, because it is not a tsquery operator anywhere.
     * {@code to_tsquery('simple', '''50,000''')} is {@code '50' <-> '000'} — the same query the bare
     * {@code 50,000} produced — and {@code to_tsvector('simple', 'deposit of 50,000 won')} puts
     * {@code '50'} and {@code '000'} at adjacent positions, so the phrase matches. The standard
     * tokenizer keeps a comma only between digits, so this is the only shape that reaches the builder.
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
     * The colon reaches its own document, which is the defect this change exists to fix.
     *
     * <p>A word-internal colon survives standard analysis intact, so {@code note:draft} reaches the
     * builder as one token exactly like the two above. It could not be sent bare — a colon is the
     * weight and prefix operator, and the failure is the statement rather than the ranking, which the
     * third assertion still runs against the server. So the allowlist deleted it and sent
     * {@code notedraft}, which matches nothing; the fourth assertion keeps that on record so a
     * regression cannot look like a pass.
     *
     * <p>Quoted, the colon is literal text: {@code 'note:draft'} parses to {@code 'note' <-> 'draft'}
     * and the row comes back.
     */
    @Test
    void aWordInternalColonMatchesItsOwnDocument() {
        store("note:draft about seoul");
        store("bob prefers tea");

        assertThat(english.tokens("note:draft")).containsExactly("note:draft");
        assertThat(conclusions.keyword(pair, english.analyze("note:draft"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("note:draft"));

        assertThatThrownBy(() -> parse("note:draft")).rootCause().hasMessageContaining("syntax error in tsquery");
        assertThat(conclusions.keyword(pair, "notedraft", 10, Filter.ALL)).isEmpty();
        assertThat(parse(TsQuery.orOf(List.of("note:draft")))).isEqualTo("'note' <-> 'draft'");
    }

    /**
     * Every printable ASCII character and every control character survives, parses, and matches.
     *
     * <p>This is the enumeration the change is owed, run rather than tabulated. For each of the 33
     * non-alphanumeric characters in {@code 32..126} and each of the 32 control characters
     * {@code U+0001..U+001F} and {@code U+007F}: {@code orOf} the term {@code a<c>b}, ask the server
     * to parse it, and ask whether it matches {@code x a<c>b y} — the document that token would have
     * been analysed from. All 65 parse and all 65 match.
     *
     * <p>The old rule matched in 5 of the 33 printable cases ({@code '} {@code ,} {@code -} {@code .}
     * {@code _}) and in none of the control cases; in the rest it produced the lexeme {@code ab},
     * which appears nowhere in the document — so reverting the quoting stops 60 of the 65 matching.
     *
     * <p>Two of the 33 are worth naming because the parser does not split them. {@code /} and {@code ~}
     * produce its {@code file} token type: {@code 'a~b'} is {@code 'a' <-> '~b'} and
     * {@code to_tsvector('simple','x a~b y')} is {@code 'a':2 'x':1 'y':4 '~b':3}, so both sides
     * agree word-internally. A <em>leading</em> {@code ~} is the one shape in this space where they do
     * not, and it is on {@link TsQuery} with its measurement: the parser folds a {@code ~} that
     * follows a blank into the blank, so {@code '~b'} matches nothing where the stripped {@code b}
     * did. It is not reachable — all three analyzers in this build return {@code [b]} for {@code ~b} —
     * and it costs a row rather than the statement.
     */
    @Test
    void everyPrintableAsciiCharacterAndControlCharacterMatchesItsOwnDocument() {
        List<Character> characters = new ArrayList<>();
        for (char c = 1; c <= 127; c++) {
            if (!Character.isLetterOrDigit(c)) {
                characters.add(c);
            }
        }
        assertThat(characters).hasSize(65);

        for (char c : characters) {
            String term = "a" + c + "b";
            String built = TsQuery.orOf(List.of(term));
            assertThatCode(() -> parse(built)).as("U+%04X must parse: %s", (int) c, built).doesNotThrowAnyException();
            assertThat(matches("x " + term + " y", built)).as("U+%04X must match its own document", (int) c).isTrue();
        }
    }

    /**
     * A backslash is escaped, or the statement dies — the escape a reader is most likely to drop.
     *
     * <p>Inside a quoted lexeme {@code \} escapes whatever follows it, so a term ending in one eats
     * the closing quote. The negative is asserted first and against the server, so that removing the
     * escape from {@code TsQuery.operand} fails a test rather than a production query: measured over
     * the 84 strings of length ≤3 from {@code {' \ <space> a}}, dropping this escape alone costs 38
     * {@code to_tsquery} rejections, more than dropping the quote escape does.
     */
    @Test
    void aBackslashIsEscapedOrTheStatementDies() {
        assertThatThrownBy(() -> parse("'foo\\' | 'bank'")).rootCause().hasMessageContaining("syntax error in tsquery");

        assertThat(parse(TsQuery.orOf(List.of("foo\\", "bank")))).isEqualTo("'foo' | 'bank'");
        assertThat(matches("x C:\\Users\\x y", TsQuery.orOf(List.of("C:\\Users\\x")))).isTrue();
    }

    /**
     * Whatever {@code orOf} returns, {@code to_tsquery} accepts — the invariant that replaces "no
     * sanitised term can open a quoted lexeme".
     *
     * <p>That property is gone: every operand opens a quoted lexeme deliberately. What holds instead
     * is that each operand is {@code '} + body + {@code '}, that the body has every {@code '} and
     * every {@code \} doubled, and that the body is never empty — {@code ''} being the one shape
     * {@code to_tsquery} rejects, alone and inside an OR chain, which the last assertion runs. The
     * hostile shapes below are the ones the old positional guard existed for, plus the two escapes'
     * own worst cases; none of them is a token an analyzer in this build hands back, but {@code orOf}
     * is public and the cost of being wrong is a failed statement rather than a worse ranking.
     */
    @Test
    void everyOperandOrOfBuildsIsOneToTsqueryAccepts() {
        for (String hostile : List.of("'foo", "foo'", "''", "'", "a''b", "'foo'", "a'", "\\", "foo\\", "\\\\", "'\\",
                "a & b", "!(a|b)", "  ", "😀", "𠀀", "नमस्ते")) {
            String built = TsQuery.orOf(List.of(hostile, "bank"));
            assertThatCode(() -> parse(built)).as("operand for %s must parse: %s", hostile, built)
                    .doesNotThrowAnyException();
        }

        // An empty term is skipped rather than emitted, because '' is the one operand that fails.
        assertThat(TsQuery.orOf(List.of("", "bank"))).isEqualTo("'bank'");
        assertThatThrownBy(() -> parse("'alice' | '' | 'bank'")).rootCause()
                .hasMessageContaining("syntax error in tsquery");
    }

    /**
     * A combining mark and a supplementary-plane code point reach the row they were analysed from.
     *
     * <p>Neither was noticed before this change, and neither is punctuation. {@code TsQuery} looped
     * over {@code char} and kept {@code Character.isLetterOrDigit(c)}, which is false for every
     * combining mark and for both halves of a surrogate pair — so {@code नमस्ते} went to the server as
     * {@code नमसत} and {@code 𠀀} as the empty string, which {@code orOf} then dropped, leaving
     * {@code keyword()} to return early with no query at all. Both analyzers emit both tokens whole,
     * measured, so the document side kept what the query side lost. The two assertions after each
     * recall pin the old form, so that reverting cannot look like a pass.
     */
    @Test
    void combiningMarksAndSupplementaryCodePointsMatchTheirOwnRows() {
        store("नमस्ते from seoul");
        store("𠀀 in the dictionary");
        store("bob prefers tea");

        assertThat(english.tokens("नमस्ते")).containsExactly("नमस्ते");
        assertThat(conclusions.keyword(pair, english.analyze("नमस्ते"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("नमस्ते"));
        assertThat(conclusions.keyword(pair, "नमसत", 10, Filter.ALL)).isEmpty();

        assertThat(english.tokens("𠀀")).containsExactly("𠀀");
        assertThat(conclusions.keyword(pair, english.analyze("𠀀"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("𠀀"));
    }

    /**
     * {@code corpusStats} and the keyword path now count the same rows.
     *
     * <p>They did not. {@code corpusStats} counted df with {@code plainto_tsquery} on the raw term,
     * which builds {@code &}, while the candidate query matches with {@code <->}: a row holding both
     * lexemes at non-adjacent positions — {@code alice draws s curves} — counted toward the document
     * frequency without ever being a candidate. On this three-row corpus that was df 3 against 2
     * matching rows, which inflates IDF and understates the term's weight for every candidate. Both
     * statements now build the operand through {@code TsQuery}, so the agreement is structural: the
     * assertion below is 2 against 2.
     */
    @Test
    void theKeywordPathAndCorpusStatsCountTheSameRows() {
        store("alice's bank statement");
        store("alice's hiking notes");
        // Holds the lexemes 'alice' and 's', never the token alice's.
        store("alice draws s curves");

        assertThat(conclusions.keyword(pair, english.analyze("alice's"), 10, Filter.ALL)).hasSize(2)
                .allSatisfy(hit -> assertThat(hit.conclusion().content()).contains("alice's"));

        var stats = conclusions.corpusStats(pair, List.of("alice's"));
        assertThat(stats.documentCount()).isEqualTo(3);
        assertThat(stats.documentFrequency().get("alice's")).isEqualTo(2L);
    }

    /**
     * The residual this change does not close, pinned with its number so nobody mistakes it for
     * closed.
     *
     * <p>df is now the candidate count, which is what the acceptance criterion asks for. It is not
     * the number of rows {@code Bm25.score} gives a non-zero {@code tf}, because that arithmetic
     * matches an exact whitespace token in {@code content_analyzed} while the SQL matches lexemes.
     * On this four-row corpus the term {@code note:draft} measures df 2 — it was 4 under
     * {@code plainto_tsquery}, which asks only that both lexemes appear somewhere — against exactly
     * one row that scores above zero. {@code note draft regarding busan} is a genuine candidate whose
     * score is 0, which is the same thing that happens to any candidate that matched on a different
     * term of an OR. The last two rows hold both lexemes without holding the phrase, which is what
     * made the old count 4.
     *
     * <p>Counting the exact token instead would make df agree with the arithmetic and disagree with
     * the candidate set, and it cannot be an index condition: measured on the real V7 schema at 200
     * and at 5 200 rows, the tsquery form keeps the text match inside the {@code Bitmap Index Scan}'s
     * Index Cond, while the exact-token form leaves it a Filter over every live row in the pair and
     * uses the index for the pair scope only.
     *
     * <p>The four rows are worded to keep dedup out of it. The stub embedder is a bag of words, so
     * four sentences over the same four words are one vector and stage 3 collapses them before the
     * query is ever asked.
     */
    @Test
    void documentFrequencyIsTheCandidateCountAndNotTheNonZeroScoreCount() {
        store("note:draft about seoul");
        store("note draft regarding busan");
        store("draft note concerning tokyo");
        store("note without a draft nearby");

        var hits = conclusions.keyword(pair, english.analyze("note:draft"), 10, Filter.ALL);
        assertThat(hits).hasSize(2);
        assertThat(hits).filteredOn(hit -> hit.score() > 0.0).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).isEqualTo("note:draft about seoul"));

        var stats = conclusions.corpusStats(pair, List.of("note:draft"));
        assertThat(stats.documentCount()).isEqualTo(4);
        assertThat(stats.documentFrequency().get("note:draft")).isEqualTo(2L);
    }

    /**
     * A curly apostrophe reaches its own document, which is the common case the geresh is not.
     *
     * <p>{@code U+2019} RIGHT SINGLE QUOTATION MARK is what a word processor, a phone keyboard and
     * most published prose put in a contraction, and Lucene's standard tokenizer keeps it inside the
     * word exactly as it keeps the ASCII apostrophe — measured here rather than assumed, because that
     * is the class of claim this file has been wrong about. It is neither a letter nor a digit, so
     * the allowlist deleted it and sent {@code dont}, which matches nothing; it is not a tsquery
     * operator either, so the operand carries it through unescaped and the row comes back.
     *
     * <p>The parser reads the two spellings the same way — {@code 'doesn’t'} and {@code 'doesn''t'}
     * are both {@code 'doesn' <-> 't'} — so the only thing that ever separated them was the
     * sanitiser, which kept one and deleted the other.
     */
    @Test
    void aCurlyApostropheMatchesItsOwnDocument() {
        store("alice doesn\u2019t like tea");
        store("bob prefers coffee");

        assertThat(english.tokens("doesn\u2019t")).containsExactly("doesn\u2019t");
        assertThat(TsQuery.orOf(List.of("doesn\u2019t"))).isEqualTo("'doesn\u2019t'");
        assertThat(parse("'doesn\u2019t'")).isEqualTo("'doesn' <-> 't'").isEqualTo(parse("'doesn''t'"));

        assertThat(conclusions.keyword(pair, english.analyze("doesn\u2019t"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("doesn\u2019t"));

        // What the allowlist used to send. Pinned so that reverting the fix cannot look like a pass.
        assertThat(conclusions.keyword(pair, "doesnt", 10, Filter.ALL)).isEmpty();
    }

    /**
     * A trailing quote does reach the builder, and it is now kept rather than dropped.
     *
     * <p>Lucene's standard tokenizer implements UAX#29, whose WB7a keeps a quote that follows a
     * Hebrew letter — the geresh, which Hebrew writes foreign sounds with. So {@code ג'ורג'} is a
     * single token with the quote on the end, and "the analyzer only ever puts a quote between two
     * alphanumerics" was false. The old positional guard dropped that quote; the operand doubles it
     * and keeps it.
     *
     * <p>The two forms are the same query — {@code 'ג''ורג'''} and the dropped {@code ג'ורג} both
     * parse to {@code 'ג' <-> 'ורג'} — so the token that motivated a guard is now handled by not
     * having one, at no cost either way. Asserted rather than reasoned from the shape, because that
     * is the claim this file has been wrong about.
     */
    @Test
    void aTrailingQuoteFromTheAnalyzerIsKept() {
        store("ג'ורג' וושינגטון");
        store("bob prefers tea");

        assertThat(english.tokens("ג'ורג'")).containsExactly("ג'ורג'");
        assertThat(TsQuery.orOf(List.of("ג'ורג'"))).isEqualTo("'ג''ורג'''");

        assertThat(parse("'ג''ורג'''")).isEqualTo("'ג' <-> 'ורג'").isEqualTo(parse("ג'ורג"));
        assertThat(conclusions.keyword(pair, english.analyze("ג'ורג'"), 10, Filter.ALL)).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).contains("ג'ורג'"));
    }

    /**
     * A row found through the keyword path carries a real BM25 score for the possessive term.
     *
     * <p>Worth asserting because the two halves of this ranking count terms differently. The SQL
     * decides candidacy through lexemes, but {@code Bm25.score} compares the raw query terms against
     * a whitespace split of {@code content_analyzed}, so its notion of the term is the analyzer's
     * token {@code alice's} — which is exactly what the query terms are. Before the apostrophe was
     * kept nothing reached this code path through {@code keyword} at all, and a possessive row only
     * ever got its score from {@code RecallService.fillMissingKeyword} after the semantic path
     * happened to surface it. A zero here would mean the SQL and the arithmetic had been reunited on
     * the candidate set and not on the term.
     */
    @Test
    void aPossessiveCandidateIsScoredOnTheTermTheAnalyzerProduced() {
        store("alice's bank statement");
        store("bob prefers tea");

        assertThat(conclusions.keyword(pair, english.analyze("alice's"), 10, Filter.ALL))
                .allSatisfy(hit -> assertThat(hit.score()).isGreaterThan(0.0));
    }
}
