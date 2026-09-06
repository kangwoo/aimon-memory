package at.aimon.memory.text;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.dict.UserDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.Analyzer;

/**
 * Korean morphological analysis via Lucene Nori.
 *
 * <p>Decompounding is set to {@code DISCARD}: for retrieval we want the parts, not the compound and
 * its parts, because keeping both inflates term frequency for long nouns and skews BM25.
 *
 * <p>Nori's known failure is splitting proper nouns it has never seen — a product or company name
 * comes back in pieces and the keyword signal degrades. The user dictionary is the fix and is wired
 * in from the start rather than retrofitted.
 */
public final class KoreanTextAnalyzer implements Analyzer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KoreanTextAnalyzer.class);

    /** Nori's own default: particles, endings, punctuation — the tags that carry no retrieval signal. */
    private static final Set<POS.Tag> STOP_TAGS = KoreanPartOfSpeechStopFilter.DEFAULT_STOP_TAGS;

    private final KoreanAnalyzer analyzer;

    public KoreanTextAnalyzer() {
        this(null);
    }

    /**
     * @param userDictionary optional path to a Nori user dictionary; each line is either a single
     *     token or {@code compound part part …}
     */
    public KoreanTextAnalyzer(Path userDictionary) {
        UserDictionary dict = loadDictionary(userDictionary);
        this.analyzer = new KoreanAnalyzer(dict, KoreanTokenizer.DecompoundMode.DISCARD, STOP_TAGS, false);
    }

    private static UserDictionary loadDictionary(Path path) {
        if (path == null) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return UserDictionary.open(reader);
        } catch (IOException e) {
            // The path is logged, not returned. `AnalyzerRegistry` builds this lazily — on the first
            // request that touches a workspace whose `language` is `ko` — so a misconfigured
            // dictionary is answered to that caller as a 500, and `MemoryException`'s message is
            // copied into the body. That handed a server filesystem path to whoever asked first.
            log.error("cannot read Nori user dictionary {}", path, e);
            throw new MemoryException("bad_user_dictionary",
                    "the configured Nori user dictionary could not be read; see the server log", e);
        }
    }

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
        return "ko";
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
