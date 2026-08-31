package dev.dyad.text;

import dev.dyad.core.spi.Analyzer;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Lucene's standard analysis: Unicode word breaking plus lowercasing, no stemming and no stop-word
 * removal.
 *
 * <p>Stop words are left in on purpose. Recall queries here are short and often consist largely of
 * function words ("what did I say about"), and dropping them costs more recall than the index space
 * they save.
 */
public final class EnglishTextAnalyzer implements Analyzer, AutoCloseable {

    private final StandardAnalyzer analyzer = new StandardAnalyzer(org.apache.lucene.analysis.CharArraySet.EMPTY_SET);

    @Override
    public String analyze(String text) {
        return String.join(" ", tokens(text));
    }

    @Override
    public List<String> tokens(String text) {
        return LuceneAnalyzerSupport.tokens(analyzer, "content", text);
    }

    @Override
    public String languageTag() {
        return "en";
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
