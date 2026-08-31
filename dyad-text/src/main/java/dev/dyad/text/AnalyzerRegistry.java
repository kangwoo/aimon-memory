package dev.dyad.text;

import dev.dyad.core.spi.Analyzer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a workspace's configured language tag to an analyzer.
 *
 * <p>Instances are shared: Lucene analyzers are thread-safe and hold a per-thread token stream, so
 * building one per request would be pure waste. Unknown tags fall back to bigrams rather than
 * throwing — a workspace configured with an unsupported language should still be searchable.
 */
public final class AnalyzerRegistry implements AutoCloseable {

    private final Map<String, Analyzer> byTag = new ConcurrentHashMap<>();
    private final Path koreanUserDictionary;

    public AnalyzerRegistry() {
        this(null);
    }

    public AnalyzerRegistry(Path koreanUserDictionary) {
        this.koreanUserDictionary = koreanUserDictionary;
    }

    public Analyzer forLanguage(String languageTag) {
        String tag = languageTag == null ? "und" : languageTag.toLowerCase(Locale.ROOT);
        String key = tag.startsWith("ko") ? "ko" : tag.startsWith("en") ? "en" : "und";
        return byTag.computeIfAbsent(key, this::create);
    }

    private Analyzer create(String key) {
        return switch (key) {
            case "ko" -> new KoreanTextAnalyzer(koreanUserDictionary);
            case "en" -> new EnglishTextAnalyzer();
            default -> new BigramTextAnalyzer();
        };
    }

    @Override
    public void close() {
        byTag.values().forEach(
                a -> {
                    if (a instanceof AutoCloseable c) {
                        try {
                            c.close();
                        } catch (Exception ignored) {
                            // closing an analyzer releases only in-memory state; nothing to recover from
                        }
                    }
                });
        byTag.clear();
    }
}
