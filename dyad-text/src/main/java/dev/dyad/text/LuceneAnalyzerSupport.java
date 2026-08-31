package dev.dyad.text;

import dev.dyad.core.DyadException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Shared plumbing for the Lucene-backed analyzers: run a {@link TokenStream}, collect the terms. */
final class LuceneAnalyzerSupport {

    private LuceneAnalyzerSupport() {}

    static List<String> tokens(Analyzer analyzer, String field, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String t = term.toString();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            stream.end();
        } catch (IOException e) {
            throw new DyadException("analyze_failed", "analysis failed for field " + field, e);
        }
        return out;
    }
}
