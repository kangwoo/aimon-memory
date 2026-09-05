package at.aimon.memory.text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import at.aimon.memory.core.model.CorpusStats;

/**
 * Okapi BM25, computed here rather than delegated to the database.
 *
 * <p>The alternative was Postgres {@code ts_rank_cd}, which is cheaper but is not BM25: it has no
 * document-length normalisation and no IDF, so a long conclusion mentioning a term once outranks a
 * short one about nothing else. It also has no stable definition across Postgres versions, and the
 * golden fixtures assert this signal to six decimal places.
 *
 * <p>Postgres still does the work it is good at — finding candidates through the GIN index and
 * counting document frequencies — and the scoring arithmetic happens where it can be tested.
 *
 * <p>k1 and b are Lucene's defaults, which is what makes the sigmoid midpoints in the fusion formula
 * transferable rather than arbitrary.
 */
public final class Bm25 {

    public static final double K1 = 1.2;
    public static final double B = 0.75;

    private Bm25() {
    }

    public static double score(List<String> queryTerms, List<String> documentTerms, CorpusStats stats) {
        if (queryTerms.isEmpty() || documentTerms.isEmpty() || stats.documentCount() == 0) {
            return 0.0;
        }
        Map<String, Integer> frequencies = new HashMap<>();
        for (String term : documentTerms) {
            frequencies.merge(term, 1, Integer::sum);
        }
        double length = documentTerms.size();
        double avgLength = stats.averageLength() <= 0 ? length : stats.averageLength();
        double total = 0.0;
        for (String term : queryTerms.stream().distinct().toList()) {
            Integer frequency = frequencies.get(term);
            if (frequency == null) {
                continue;
            }
            long df = stats.documentFrequency().getOrDefault(term, 0L);
            double idf = idf(stats.documentCount(), df);
            double denominator = frequency + K1 * (1 - B + B * length / avgLength);
            total += idf * (frequency * (K1 + 1)) / denominator;
        }
        return total;
    }

    /** Lucene's variant, which cannot go negative even when a term appears in most documents. */
    public static double idf(long documentCount, long documentFrequency) {
        return Math.log(1 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }
}
