package at.aimon.memory.text;

import java.util.ArrayList;
import java.util.List;

import at.aimon.memory.core.spi.Analyzer;

/**
 * Language-agnostic fallback: character bigrams over CJK runs, whole words elsewhere.
 *
 * <p>This is what you reach for when a morphological analyzer is unavailable or is mangling the
 * domain vocabulary. It is worse than Nori on Korean, but it degrades predictably instead of
 * emitting the whole sentence as one token the way a whitespace tokenizer does — and unlike a pure
 * bigram scheme it does not shred Latin words that were already fine.
 */
public final class BigramTextAnalyzer implements Analyzer {

    @Override
    public String analyze(String text) {
        return String.join(" ", tokens(text));
    }

    @Override
    public List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String norm = Normalizer.normalize(text);
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = norm.length();
        while (i < n) {
            char c = norm.charAt(i);
            if (isIdeographic(c)) {
                int start = i;
                while (i < n && isIdeographic(norm.charAt(i))) {
                    i++;
                }
                emitBigrams(norm, start, i, out);
            } else if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < n && Character.isLetterOrDigit(norm.charAt(i)) && !isIdeographic(norm.charAt(i))) {
                    i++;
                }
                out.add(norm.substring(start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    private static void emitBigrams(String s, int start, int end, List<String> out) {
        if (end - start == 1) {
            out.add(s.substring(start, end));
            return;
        }
        for (int i = start; i < end - 1; i++) {
            out.add(s.substring(i, i + 2));
        }
    }

    private static boolean isIdeographic(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }

    @Override
    public String languageTag() {
        return "und";
    }
}
