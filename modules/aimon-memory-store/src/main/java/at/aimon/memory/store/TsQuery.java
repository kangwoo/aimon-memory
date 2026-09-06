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
 * {@code note:draft} arrive as single tokens and leave as {@code alices}, {@code 50000} and
 * {@code notedraft}, none of which match the document they were analysed from. Measured, and still
 * stripped on purpose: {@code TsQueryTest.punctuationThatSurvivesAnalysisIsStrippedAndLosesItsMatch}
 * holds the numbers, and which of the three could be kept safely.
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
 * matches on its own.
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

    private static String sanitize(String term) {
        StringBuilder sb = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
