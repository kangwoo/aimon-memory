package at.aimon.memory.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds a {@code tsquery} that ORs the analysed query terms, one quoted operand per term.
 *
 * <p>{@code plainto_tsquery} ANDs everything, which for recall is the wrong default — a five-word
 * question would only match a conclusion containing all five. Requiring every term turns a ranking
 * problem into a filtering one and drops the candidates fusion exists to reorder.
 *
 * <p><b>Terms are quoted rather than stripped, and that is the whole of the change from an allowlist
 * to a rule.</b> What stood here deleted every character the tsquery grammar could read as an
 * operator, and the deletion is what cost recall: both sides of the index are output of the same
 * analyzer, so a term the sanitiser rewrote no longer matched the document it was analysed from.
 * {@code note:draft} went to the server as {@code notedraft} against a tsvector of
 * {@code 'note':1 'draft':2}, and — measured below, and not previously noticed — every token
 * carrying a combining mark or a supplementary-plane code point was mutilated the same way. Inside a
 * quoted lexeme only two characters mean anything to the tsquery lexer, so nothing has to be
 * deleted. The price is that <em>every</em> operator character now reaches the parser as literal
 * text, which is enumerated rather than argued about.
 *
 * <p>Every number below was run against PostgreSQL 16.15 ({@code pgvector/pgvector:pg16},
 * {@code en_US.utf8}, the image and locale {@code PostgresSupport} gives the integration tier), and
 * against this build's own analyzers where the claim is about a token. The locale is load-bearing
 * for one row: under {@code --locale=C} the emoji case below inverts.
 *
 * <p><b>What newly survives, word-internally.</b> All 33 non-alphanumeric characters in
 * {@code 32..126}, as the term {@code a<c>b} against the document {@code x a<c>b y}: 33 of 33 parse
 * without error and 33 of 33 match. The allowlist matched 5 of the 33 ({@code '} {@code ,} {@code -}
 * {@code .} {@code _}) and in the other 28 produced the lexeme {@code ab}, which appears nowhere in
 * the document. What the parser makes of the quoted operand:
 *
 * <ul>
 * <li>{@code 'a' <-> 'b'} for 29 of them — the whole of
 *     {@code <space> ! " # $ % & ' ( ) * + , : ; < = > ? @ [ \ ] ^ _ ` { | }};</li>
 * <li>{@code 'a-b' <-> 'a' <-> 'b'} for {@code -}, {@code 'a.b'} for {@code .}, {@code 'a/b'} for
 *     {@code /}, and {@code 'a' <-> '~b'} for {@code ~}.</li>
 * </ul>
 *
 * <p>So 28 printable-ASCII characters newly survive word-internally:
 * {@code <space> ! " # $ % & ( ) * + / : ; < = > ? @ [ \ ] ^ ` { | } ~}. The 32 control characters
 * {@code U+0001..U+001F} and {@code U+007F} newly survive too — every one parses to
 * {@code 'a' <-> 'b'} and matches its own document, 0 errors.
 *
 * <p><b>The apostrophe is position-dependent, and the list above only counts one of its positions.</b>
 * Between two alphanumerics it already survived — that is the shape the allowlist admitted. Bare,
 * leading, trailing and doubled it did not: the guard dropped the quote from all four, so all four
 * are newly surviving. Measured: {@code '} → operand {@code ''''} → an empty tsquery with a NOTICE
 * and no error; {@code 'a} → {@code '''a'} → {@code 'a'}; {@code a'} → {@code 'a'''} → {@code 'a'};
 * {@code a''b} → {@code 'a''''b'} → {@code 'a' <-> 'b'}. None of those was reachable before and none
 * of them is an error now.
 *
 * <p><b>{@code /} and {@code ~} give the default parser's {@code file} token type, and a leading
 * {@code ~} is the only character in printable ASCII where quoting matches strictly less than the
 * allowlist did.</b> Word-internally — the scope of the table above — both sides agree and the row
 * comes back:
 * {@code ts_debug('simple','a~b')} is {@code asciiword a} then {@code file ~b}, and
 * {@code to_tsvector('simple','x a~b y')} is {@code 'a':2 'x':1 'y':4 '~b':3}, which
 * {@code 'a' <-> '~b'} matches. <b>A leading {@code ~} does not.</b> The parser only yields the
 * {@code file} token at the start of the input: {@code to_tsvector('simple','~b y')} is
 * {@code 'y':2 '~b':1}, but {@code ts_debug('simple','x ~b y')} folds a {@code ~} that follows a
 * blank into the blank, so {@code to_tsvector('simple','x ~b y')} is {@code 'b':2 'x':1 'y':3} and
 * the operand {@code '~b'} — which parses to {@code '~b'} — matches nothing, where the stripped
 * {@code b} did. Run over {@code 1..127} in four positions (bare, leading, trailing, word-internal),
 * comparing old match against new match on each character's own document: 0 regressions bare, 0
 * trailing, 0 word-internal (60 gains), and exactly 1 leading — {@code ~}. It is not reachable from
 * this build: measured, {@code ~b} is {@code [b]} out of {@code EnglishTextAnalyzer} and
 * {@code KoreanTextAnalyzer} alike, and {@code BigramTextAnalyzer} emits letter/digit runs only. And
 * it costs a row rather than the statement, which is why it is accepted rather than guarded — a
 * guard would be a fresh claim about the token universe, which is the mistake this file has made
 * three times. "Both sides agree" is a property of {@code /} and of word-internal {@code ~}, not of
 * {@code ~}.
 *
 * <p>That sweep is ASCII, and so is the claim. Outside it the same one-directional loss exists
 * wherever a term's mark-stripped form happened to appear in some document: {@code Normalizer} trims
 * and lowercases without composing, so NFD {@code café} reaches the builder decomposed, the allowlist
 * sent {@code cafe} and that <em>matched</em> a row reading {@code cafe latte}, where the operand
 * {@code 'café'} does not. Measured, and the same measurement shows what was traded for it: the
 * stripped form did <b>not</b> match the row the token was analysed from and the operand does. Those
 * were accidental matches on a spelling the query did not use, and losing them is the fix rather than
 * a cost of it.
 *
 * <p><b>What no longer gets mutilated, which nobody had counted.</b> The old loop walked
 * {@code char} and kept {@code Character.isLetterOrDigit(c)}. Two whole classes fall outside that
 * predicate, and both analyzers emit them intact — measured, not assumed. Combining marks
 * ({@code Mn}/{@code Mc}/{@code Me}) are neither letters nor digits, so every Devanagari virama,
 * Thai vowel sign, Hebrew niqqud, Arabic harakat and NFD accent was deleted from the query term
 * while the document side kept it: {@code नमस्ते} became {@code नमसत}, {@code עִברִית} became
 * {@code עברית}, NFD {@code café} became {@code cafe} — each matching nothing at all, each matching
 * now. Supplementary-plane code points were deleted outright, because
 * {@code Character.isLetterOrDigit(char)} is false for both surrogates even though it is true for
 * the code point ({@code 0x20000}, verified both ways): a single Ext-B ideograph {@code 𠀀}
 * sanitised to the empty string and was dropped from the query altogether. It matches now.
 *
 * <p><b>Non-ASCII punctuation survives too, and one shape of it is common rather than exotic.</b>
 * The standard tokenizer keeps {@code U+2019} RIGHT SINGLE QUOTATION MARK inside a word the way it
 * keeps the ASCII apostrophe, so {@code EnglishTextAnalyzer.tokens("don’t")} is one token — measured,
 * and {@code it’s alice’s} is two. The allowlist sent {@code dont}, which matched nothing; the
 * operand {@code 'don’t'} parses to {@code 'don' <-> 't'} and matches {@code x don’t y}, whose
 * tsvector is {@code 'don':2 't':3 'x':1 'y':4}. A curly apostrophe is far likelier in real text than
 * the geresh below, which is why it is named here and run in
 * {@code KeywordPunctuationTest.aCurlyApostropheMatchesItsOwnDocument} rather than left to the
 * general argument. It is not alone: measured as {@code a<c>b}, {@code U+2018}, {@code U+00B7} and
 * {@code U+FF1A} also survive standard analysis inside the token, and {@code U+201C}, {@code U+2014},
 * {@code U+2013}, {@code U+00A0} and {@code U+3001} split it — all nine parse and match under
 * quoting, and none of them did before. Nothing outside ASCII is an operator inside a quoted lexeme,
 * so the safety half of the enumeration needs no separate argument for them.
 *
 * <p>{@code 😀} is the honest counter-example, and the reason no "does this contain a letter or
 * digit" filter belongs here: both analyzers emit it, and its quoted operand produces an
 * <em>empty</em> tsquery — no lexeme, a NOTICE, no error. Whether an operand yields a lexeme is a
 * question only the server's parser answers ({@code '~b'} yields one, {@code '😀'} does not), so
 * predicting it in Java would be a fourth over-general claim. An operand with no lexeme in it is
 * dropped from an OR chain in silence — {@code 'alice' | '!!!' | 'bank'} is
 * {@code 'alice' | 'bank'} — which is the behaviour {@code -}, {@code .}, {@code _} and {@code ,}
 * have always had here.
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
 * Quoting is a no-op for all of these: {@code 'foo-bar'}, {@code '50,000'}, {@code 'snake_case'},
 * {@code 'e.g'}, {@code '192.168.0.1'} and {@code 'alice''s'} parse to exactly what the bare forms
 * parse to. Over all 1 606 strings of length ≤4 from {@code {a 1 _ - . , '}} that the old sanitiser
 * passed through unchanged — every shape that survived it — quoted and bare parse identically: 0
 * differences, 0 errors either way.
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
 *
 * <p><b>The geresh, which used to motivate a guard and now motivates the absence of one.</b> Lucene's
 * standard tokenizer implements UAX#29, whose WB7a keeps a quote that follows a Hebrew letter, so
 * {@code ג'ורג'} is a single token with the quote on the end. The allowlist dropped that quote; the
 * operand keeps it, and the two are the same query — {@code 'ג''ורג'''} and the old {@code ג'ורג}
 * both parse to {@code 'ג' <-> 'ורג'} and match the same rows. The token that was the reason for a
 * positional rule is now handled by not having one.
 *
 * <p>The two characters that are still not literal, and the proof that both escapes are load-bearing,
 * are on {@link #operand(String)}.
 */
public final class TsQuery {

    private TsQuery() {
    }

    /**
     * The terms as a disjunction of quoted operands, duplicates dropped, empties skipped.
     *
     * <p><b>The invariant this class now offers.</b> For any list of terms, {@code orOf} returns
     * either the empty string, or a string {@code to_tsquery('simple', ·)} accepts — with two
     * measured exceptions, both pre-existing and neither introduced by quoting. A term containing
     * {@code U+0000} is rejected by the JDBC driver before the server sees it
     * ({@code invalid byte sequence for encoding "UTF8": 0x00}, measured for both this string and
     * the {@code text[]} parameter {@code corpusStats} already passes raw terms through), and around
     * 16 000 operands is {@code ERROR: stack depth limit exceeded} — measured identically quoted and
     * bare, 12 000 and 14 000 parse, 16 000 and 20 000 do not, at {@code max_stack_depth = 2MB}.
     *
     * <p>The guarantee rests on three things: each operand is {@code '} + body + {@code '}; the body
     * has every {@code '} and every {@code \} doubled; and the body is non-empty, which is the only
     * shape that produces the {@code ''} syntax error. It replaces the invariant that used to be
     * pinned here — <em>no sanitised term can open a quoted lexeme</em> — which is gone because
     * every operand now opens one deliberately.
     *
     * <p>Deduplication is on the <em>raw</em> term rather than on what is emitted. Stripping used to
     * collapse terms that differed only in punctuation, so {@code ["alice","alice!","(alice)"]} came
     * out as {@code alice}; it is now {@code 'alice' | 'alice!' | '(alice)'}, which the server parses
     * to {@code 'alice' | 'alice' | 'alice'} — {@code to_tsquery} does not collapse duplicate
     * operands, measured. Same rows, longer string, and {@code ts_rank_cd} is unchanged by the
     * repetition, which matters because {@code keyword()} orders its candidates by it.
     */
    public static String orOf(List<String> terms) {
        List<String> operands = new ArrayList<>(terms.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String term : terms) {
            String quoted = operand(term);
            if (!quoted.isEmpty() && seen.add(term)) {
                operands.add(quoted);
            }
        }
        return String.join(" | ", operands);
    }

    /**
     * One term as a quoted tsquery operand, or the empty string if there is nothing to quote.
     *
     * <p>Inside a quoted lexeme the tsquery lexer reads exactly two characters: {@code '} closes the
     * lexeme and {@code \} escapes whatever follows it. Both are doubled and nothing else is touched.
     *
     * <p><b>Both escapes are load-bearing, and the evidence is a mutation rather than an argument.</b>
     * Over all 84 strings of length ≤3 drawn from {@code {' , \ , <space>, a}}, counting how many
     * {@code to_tsquery} rejects: with both doubled, <b>0</b>; with the quote doubled only, 24; with
     * the backslash doubled only, 38; with neither, 45. The named shapes:
     * {@code 'foo\' | 'bank'} is {@code ERROR: syntax error in tsquery} while
     * {@code 'foo\\' | 'bank'} is {@code 'foo' | 'bank'}, and {@code ''} is a syntax error both alone
     * and inside an OR chain, which is why an empty term returns {@code ""} here and is skipped by
     * {@link #orOf(List)} rather than joined as an empty operand. Wider fuzz with both escapes, over
     * all 258 strings of length ≤3 from {@code {' , \ , <space>, a, !, ~}}: 0 errors alone, 0 errors
     * OR-ed with {@code 'bank'}.
     *
     * <p>Public because {@code ...store.repo} is a different package and
     * {@code ConclusionRepository.corpusStats} counts document frequency with the same operand this
     * builds. That is deliberate: the escaping rule exists once, so the two statements agree by
     * construction rather than by two functions happening to behave alike.
     */
    public static String operand(String term) {
        if (term == null || term.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(term.length() + 4).append('\'');
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c == '\'' || c == '\\') {
                sb.append(c);
            }
            sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
