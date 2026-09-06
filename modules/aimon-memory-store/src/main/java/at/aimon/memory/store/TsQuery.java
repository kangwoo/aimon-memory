package at.aimon.memory.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@code tsquery} that ORs the analysed query terms.
 *
 * <p>{@code plainto_tsquery} ANDs everything, which for recall is the wrong default — a five-word
 * question would only match a conclusion containing all five. Requiring every term turns a ranking
 * problem into a filtering one and drops the candidates fusion exists to reorder.
 *
 * <p>Terms are stripped of tsquery operator characters rather than escaped. What stood here was that
 * this costs nothing — "the input is already analyzer output, so anything removed was not a real
 * token to begin with" — and that is false. Lucene's standard tokenizer keeps an apostrophe, a
 * thousands separator and a word-internal colon, so {@code alice's}, {@code 50,000} and
 * {@code note:draft} arrive as single tokens; all three used to leave as {@code alices},
 * {@code 50000} and {@code notedraft}, none of which match the document they were analysed from. Two
 * of the three are now kept. Which two, and why the third is not, is the whole of
 * {@link #sanitize(String)}.
 *
 * <p><b>Why a term keeping its hyphen, underscore or dot is not the AND it looks like.</b> Checked
 * against a real Postgres 16 rather than reasoned about. {@code to_tsquery('simple', 'foo-bar | baz')}
 * is {@code 'foo-bar' <-> 'foo' <-> 'bar' | 'baz'}, and {@code 'snake_case'} is
 * {@code 'snake' <-> 'case'}: the parser splits a compound and rejoins the parts with {@code <->},
 * which demands every part, adjacent and in order — on its face the intersection this class exists
 * to avoid.
 *
 * <p>It costs nothing because the document side splits identically.
 * {@code to_tsvector('simple', 'foo-bar baz')} is {@code 'foo-bar':1 'foo':2 'bar':3 'baz':4}, so the
 * phrase matches the very text the compound came from, and the {@code |} between terms is untouched.
 * Measured for every punctuated form that actually reaches here — {@code snake_case}, {@code e.g},
 * {@code 192.168.0.1} — each matches a document containing it, and a term OR-ed beside one still
 * matches on its own. The same argument is what makes the apostrophe and the comma safe below: both
 * produce {@code <->} against a document the analyzer split the same way.
 *
 * <p>The phrase form would lose recall only if the query carried a compound the document did not, and
 * that asymmetry is unreachable: both sides are output of the same analyzer. The loss is real if it
 * ever happens — a {@code 'foo-bar'} query over six rows finds 1 of the 5 that a plain OR finds — but
 * no analyzer in this build emits a hyphen at all: Lucene's standard tokenizer, Nori and the bigram
 * fallback all break on it, so {@code content_analyzed} and the query terms are hyphen-free together.
 * The one way a hyphen reaches this builder is a Nori user-dictionary entry containing one
 * ({@code AIMON_MEMORY_NORI_USER_DICT}), and that reaches both sides at once, so it still matches —
 * verified for {@code 포스코-케미칼} and {@code foo-bar}. Left alone deliberately. What would break it
 * is re-analysing one side and not the other, which is not specific to hyphens.
 */
public final class TsQuery {

    private TsQuery() {
    }

    public static String orOf(List<String> terms) {
        List<String> safe = new ArrayList<>(terms.size());
        for (String term : terms) {
            String cleaned = sanitize(term);
            if (!cleaned.isEmpty() && !safe.contains(cleaned)) {
                safe.add(cleaned);
            }
        }
        return String.join(" | ", safe);
    }

    /**
     * Drops what {@code to_tsquery} would read as an operator, and nothing else.
     *
     * <p>Three characters that a real analyzer emits inside a token get three different answers, and
     * the reason they differ is the tsquery grammar rather than taste. Each was run against a real
     * Postgres 16; the shape of the failure is what decides the treatment.
     *
     * <p><b>{@code :} is removed, everywhere.</b> It is a genuine operator — the weight and prefix
     * suffix — and a term carrying one is not a worse match, it is a failed statement:
     * {@code to_tsquery('simple', 'g:h')} and {@code to_tsquery('simple', 'a | g:h')} are both
     * {@code ERROR: syntax error in tsquery}. Position cannot rescue it the way it rescues the
     * apostrophe below: {@code g:h} already has an alphanumeric on both sides. The only recovery is
     * to quote the operand — {@code to_tsquery('simple', '''note:draft''')} is
     * {@code 'note' <-> 'draft'} and does match {@code to_tsvector('simple', 'note:draft here')},
     * where the stripped {@code notedraft} matches nothing — and quoting is a change to every
     * operand, not a character in an allowlist. So {@code note:draft} still costs its match. Left
     * that way on purpose; see {@code TsQueryTest.aWordInternalColonIsStillStripped}.
     *
     * <p><b>{@code '} is kept, but only between two alphanumerics.</b> Bare, it opens a quoted
     * lexeme that never closes: {@code to_tsquery('simple', '''foo')} and
     * {@code to_tsquery('simple', 'bank | ''foo | seoul')} are syntax errors, and so is a term that
     * is nothing but a quote. Word-internal it is not an operator at all —
     * {@code to_tsquery('simple', 'alice''s | bank')} is {@code 'alice' <-> 's' | 'bank'}, which
     * matches {@code to_tsvector('simple', 'alice''s bank')} = {@code 'alice':1 's':2 'bank':3}. So
     * the position test is the whole fix: it admits exactly the form the analyzer produces and
     * excludes exactly the form that fails. Because an alphanumeric is itself always kept, a kept
     * quote still has an alphanumeric on each side of it in the output, so a sanitised term can never
     * begin with a quote — and beginning with one is the whole of the hazard. Measured, because the
     * failure is narrower than the shape suggests: a quote only opens a lexeme when it <em>starts</em>
     * the operand, so {@code to_tsquery('simple', 'foo''')} is {@code 'foo'} and
     * {@code to_tsquery('simple', 'a''''b')} is {@code 'a' <-> 'b'}, neither of them an error, while
     * {@code ''} is one because it opens a lexeme it then closes empty. The positional rule is
     * stricter than that single property on purpose, and the margin is free — not because the extra
     * shapes are unreachable, which is what stood here and is false (see below), but because a quote
     * the guard drops was doing nothing: {@code to_tsquery('simple', 'foo''')} and
     * {@code to_tsquery('simple', 'foo')} are the same {@code 'foo'}.
     *
     * <p><b>{@code ,} is kept unconditionally.</b> It is not an operator in any position, so there is
     * no bare case to guard: {@code to_tsquery('simple', 'alice | 50,000')} is
     * {@code 'alice' | '50' <-> '000'}, and an operand that is nothing but commas is dropped in
     * silence rather than raised — {@code 'foo | ,,, | bar'} is {@code 'foo' | 'bar'}. Alone it is an
     * empty tsquery with a "doesn't contain lexemes" NOTICE and no error, which is the behaviour
     * {@code -}, {@code .} and {@code _} have always had here.
     *
     * <p>Both of the kept characters reach this builder only from the English analyzer — measured over
     * all three analyzers in this build, not assumed; Nori and the bigram fallback split on both. The
     * comma arrives only between digits ({@code 50,000} survives, {@code x,y} does not). The
     * apostrophe arrives in <em>two</em> positions rather than one, and the second is why the sentence
     * that stood here — "only in the position it is kept in" — was the third over-general claim this
     * file has carried about analyzer output. Word-internal is one: {@code alice's}, {@code a'b}.
     * Trailing is the other: Lucene's standard tokenizer implements UAX#29, whose WB7a keeps a quote
     * that follows a Hebrew letter, so the geresh Hebrew writes foreign sounds with stays attached and
     * {@code ג'ורג'} is a single token, quote and all. The guard drops that quote and no match is
     * lost, because the parser was ignoring it anyway: {@code ג'ורג'} and {@code ג'ורג} are both
     * {@code 'ג' <-> 'ורג'}. Run rather than argued, in
     * {@code KeywordPunctuationTest.aTrailingQuoteFromTheAnalyzerCostsNoMatchWhenDropped}.
     *
     * <p>A <em>leading</em> quote is the shape that is not known to reach here, and it is the only one
     * that would matter: {@code orOf} is public, and a term arriving with one would take the whole
     * statement down rather than return a worse ranking. The boundary cases pinned in
     * {@code TsQueryTest} rest on that asymmetry rather than on a claim about what the analyzers emit.
     */
    private static String sanitize(String term) {
        StringBuilder sb = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ',') {
                sb.append(c);
            } else if (c == '\'' && isBetweenAlphanumerics(term, i)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Read on the original term rather than on what has been appended so far.
     *
     * <p>The two differ, and reading the buffer would be the weaker test: in {@code a'&'b} the
     * characters flanking each quote are alphanumeric in neither string, but in {@code a':b} the
     * colon is about to be dropped, so a buffer-based check would see {@code a} then {@code b} around
     * a quote that in fact separates nothing and let it through. Judging the input keeps the rule the
     * one that was verified — "the analyzer put this quote inside a word" — and both of those terms
     * come out quote-free.
     */
    private static boolean isBetweenAlphanumerics(String term, int index) {
        return index > 0 && index + 1 < term.length() && Character.isLetterOrDigit(term.charAt(index - 1))
                && Character.isLetterOrDigit(term.charAt(index + 1));
    }
}
