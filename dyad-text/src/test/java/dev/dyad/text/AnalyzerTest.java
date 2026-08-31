package dev.dyad.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzerTest {

    @Test
    void koreanAnalyzerSplitsMorphemesAndDropsParticles() {
        try (KoreanTextAnalyzer analyzer = new KoreanTextAnalyzer()) {
            List<String> tokens = analyzer.tokens("앨리스는 서울 강남에서 일한다");

            // The particles 는 and 에서 carry no retrieval signal and must not become terms.
            assertThat(tokens).contains("서울", "강남");
            assertThat(tokens).doesNotContain("는", "에서");
            assertThat(analyzer.languageTag()).isEqualTo("ko");
        }
    }

    @Test
    void koreanAnalysisIsStableAcrossCalls() {
        try (KoreanTextAnalyzer analyzer = new KoreanTextAnalyzer()) {
            String once = analyzer.analyze("앨리스는 서울에서 커피를 마신다");
            String twice = analyzer.analyze("앨리스는 서울에서 커피를 마신다");
            // content_analyzed is stored, so instability here would mean the index disagrees with the
            // query path for the same text.
            assertThat(once).isEqualTo(twice);
        }
    }

    @Test
    void englishAnalyzerLowercasesAndKeepsStopWords() {
        try (EnglishTextAnalyzer analyzer = new EnglishTextAnalyzer()) {
            assertThat(analyzer.tokens("Alice works AT a Bank"))
                    .containsExactly("alice", "works", "at", "a", "bank");
        }
    }

    @Test
    void bigramFallbackShredsCjkButKeepsLatinWords() {
        BigramTextAnalyzer analyzer = new BigramTextAnalyzer();

        // Latin words stay whole; a whitespace tokenizer on Korean would emit one huge token instead.
        assertThat(analyzer.tokens("alice 서울강남 2024"))
                .containsExactly("alice", "서울", "울강", "강남", "2024");
        assertThat(analyzer.languageTag()).isEqualTo("und");
    }

    @Test
    void registryResolvesByLanguageAndFallsBackForUnknownTags() {
        try (AnalyzerRegistry registry = new AnalyzerRegistry()) {
            assertThat(registry.forLanguage("ko-KR").languageTag()).isEqualTo("ko");
            assertThat(registry.forLanguage("en-GB").languageTag()).isEqualTo("en");
            assertThat(registry.forLanguage("fr").languageTag()).isEqualTo("und");
            assertThat(registry.forLanguage(null).languageTag()).isEqualTo("und");

            // Shared instances: building an analyzer per request would dominate recall latency.
            assertThat(registry.forLanguage("ko")).isSameAs(registry.forLanguage("ko"));
        }
    }
}
