package at.aimon.memory.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.model.CorpusStats;

class Bm25Test {

    private static final CorpusStats STATS = new CorpusStats(1000, 10.0, Map.of("서울", 50L, "강남", 20L, "the", 900L));

    @Test
    void rarerTermsContributeMore() {
        double common = Bm25.idf(1000, 900);
        double rare = Bm25.idf(1000, 20);
        assertThat(rare).isGreaterThan(common);
        // Lucene's variant stays non-negative even for a term in nearly every document.
        assertThat(common).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shorterDocumentsScoreHigherForTheSameMatch() {
        List<String> query = List.of("서울");
        double shortDoc = Bm25.score(query, List.of("앨리스", "서울", "거주"), STATS);
        double longDoc = Bm25.score(query,
                List.of("앨리스", "서울", "거주", "그리고", "여행", "취미", "커피", "독서", "영화", "음악", "요리", "운동"), STATS);
        assertThat(shortDoc).isGreaterThan(longDoc);
    }

    @Test
    void unmatchedQueriesScoreZero() {
        assertThat(Bm25.score(List.of("부산"), List.of("서울", "강남"), STATS)).isZero();
        assertThat(Bm25.score(List.of(), List.of("서울"), STATS)).isZero();
        assertThat(Bm25.score(List.of("서울"), List.of(), STATS)).isZero();
        assertThat(Bm25.score(List.of("서울"), List.of("서울"), CorpusStats.empty())).isZero();
    }

    @Test
    void scoreIsDeterministic() {
        List<String> query = List.of("서울", "강남");
        List<String> document = List.of("앨리스", "서울", "강남", "근무");
        assertThat(Bm25.score(query, document, STATS)).isEqualTo(Bm25.score(query, document, STATS));
    }
}
