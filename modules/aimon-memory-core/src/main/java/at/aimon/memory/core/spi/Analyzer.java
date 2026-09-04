package at.aimon.memory.core.spi;

import java.util.List;

/**
 * Language analysis, kept behind an interface so Korean is a configuration choice rather than a
 * rewrite.
 *
 * <p>Both source systems hardcoded English here — one through spaCy, the other through
 * {@code to_tsvector('english', …)} — and both lose the keyword signal entirely on Korean text. aimon-memory
 * analyses at write time into a stored column and indexes that with the {@code simple} dictionary,
 * which takes the language decision out of the index and puts it here.
 */
public interface Analyzer {

    /** Space-joined analysed form, stored in {@code content_analyzed} and indexed for BM25. */
    String analyze(String text);

    /** The same analysis as a token list, for the BM25 scorer and the dedup token-set score. */
    List<String> tokens(String text);

    /** BCP-47 tag identifying this analyzer, e.g. {@code ko}, {@code en}, {@code und}. */
    String languageTag();
}
