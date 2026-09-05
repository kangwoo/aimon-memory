package at.aimon.memory.core.model;

import java.util.Map;

/**
 * Corpus statistics for one pair's conclusions.
 *
 * <p>Lives here rather than nested in {@code text.Bm25} because a {@code ConclusionStore} has to
 * return one: counting document frequencies is work the database does, and the scoring arithmetic
 * that consumes them is in {@code aimon-memory-text}. Keeping the record in {@code text} would have
 * put a {@code text} type in a {@code core} SPI signature, which {@code core} cannot see — it
 * depends on nothing, and that is the property everything else here rests on.
 *
 * <p>The split is along the right seam anyway: this is data, {@code Bm25} is the algorithm.
 *
 * @param documentCount N
 * @param averageLength average token count per document
 * @param documentFrequency df per query term; a term absent from the map has df 0
 */
public record CorpusStats(long documentCount, double averageLength, Map<String, Long> documentFrequency) {

    public CorpusStats {
        documentFrequency = Map.copyOf(documentFrequency);
    }

    public static CorpusStats empty() {
        return new CorpusStats(0, 0, Map.of());
    }
}
