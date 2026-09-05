package at.aimon.memory.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.spi.EmbedPurpose;

/**
 * The embedder a deployment gets when it configures none.
 *
 * <p>{@code aimon.memory.embed.provider} defaults to {@code hashing}, so this is the vector source in
 * every demo, every developer's local run and the whole default-configuration path — and it had no
 * test of its own. What it must not do is more interesting than what it does: a zero vector has no
 * cosine distance to anything, so it would make pgvector's {@code <=>} meaningless for the row it was
 * written to, and dedup stage 3 reads that distance.
 */
class HashingEmbedderTest {

    private static final int DIMENSIONS = 64;

    private final HashingEmbedder embedder = new HashingEmbedder(DIMENSIONS);

    private static double norm(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }

    @Test
    void vectorsHaveTheConfiguredWidth() {
        assertThat(embedder.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(embedder.embed("alice works at a bank", EmbedPurpose.DOCUMENT)).hasSize(DIMENSIONS);
    }

    /**
     * The width has to be the one the column was created at, so a caller that reconfigures dimensions
     * gets vectors of the new width rather than the default.
     */
    @Test
    void theWidthFollowsTheConfiguredValue() {
        assertThat(new HashingEmbedder(1536).embed("alice", EmbedPurpose.DOCUMENT)).hasSize(1536);
    }

    @Test
    void everyVectorIsUnitLength() {
        for (String text : List.of("alice works at a bank", "서울 강남", "x", "1234567890")) {
            assertThat(norm(embedder.embed(text, EmbedPurpose.DOCUMENT))).isCloseTo(1.0, within(1e-6));
        }
    }

    /**
     * Text with no tokens still produces a unit vector rather than all zeros.
     *
     * <p>The guard exists because a zero vector is not a point on the unit sphere and pgvector's
     * cosine distance to it is undefined — the row would be silently unrankable, and dedup would read
     * a meaningless distance for it. Blank content is reachable: {@code OpenAiEmbedder} substitutes a
     * space for it, and an analyzer can strip a short sentence to nothing.
     */
    @Test
    void textWithNoTokensStillProducesAUsableVector() {
        for (String empty : List.of("", "   ", "!!!", "\n\t")) {
            float[] vector = embedder.embed(empty, EmbedPurpose.DOCUMENT);
            assertThat(norm(vector)).isCloseTo(1.0, within(1e-6));
        }
    }

    /** Same text, same vector — dedup stage 1 and the golden fixtures both rest on this. */
    @Test
    void embeddingIsDeterministic() {
        assertThat(embedder.embed("alice works at a bank", EmbedPurpose.DOCUMENT))
                .isEqualTo(embedder.embed("alice works at a bank", EmbedPurpose.DOCUMENT));
        assertThat(new HashingEmbedder(DIMENSIONS).embed("서울 강남", EmbedPurpose.DOCUMENT))
                .isEqualTo(embedder.embed("서울 강남", EmbedPurpose.DOCUMENT));
    }

    /**
     * The purpose does not change the vector.
     *
     * <p>A real provider may embed a query differently from a document; this one cannot, and recall
     * compares a QUERY vector against DOCUMENT vectors in the same space. If the purpose ever started
     * to matter here, every stored vector would stop being comparable to any query.
     */
    @Test
    void thePurposeDoesNotChangeTheVector() {
        assertThat(embedder.embed("서울", EmbedPurpose.QUERY)).isEqualTo(embedder.embed("서울", EmbedPurpose.DOCUMENT))
                .isEqualTo(embedder.embed("서울", EmbedPurpose.ENTITY));
    }

    /**
     * Similarity tracks lexical overlap, which is the entire claim this stand-in makes.
     *
     * <p>Korean is the case the bigram tokenisation was chosen for: a whitespace split makes
     * {@code 강남} and {@code 강남에서} share no token at all, so a demo query about a place would
     * score zero against a conclusion about that place and read as a broken ranker rather than as the
     * limit of a stand-in embedder.
     */
    @Test
    void overlappingTextScoresHigherThanUnrelatedText() {
        float[] query = embedder.embed("강남", EmbedPurpose.QUERY);
        float[] overlapping = embedder.embed("앨리스는 강남에서 일한다", EmbedPurpose.DOCUMENT);
        float[] unrelated = embedder.embed("오늘 날씨가 아주 춥다", EmbedPurpose.DOCUMENT);

        assertThat(cosine(query, overlapping)).isGreaterThan(cosine(query, unrelated));
    }

    @Test
    void identicalTextIsMaximallySimilar() {
        float[] a = embedder.embed("alice works at a bank", EmbedPurpose.DOCUMENT);
        float[] b = embedder.embed("alice works at a bank", EmbedPurpose.QUERY);

        assertThat(cosine(a, b)).isCloseTo(1.0, within(1e-6));
    }

    /**
     * The batch is positionally aligned with its input, which is the contract every caller indexes on
     * and which {@code Embedder.requireAligned} checks at the four call sites.
     */
    @Test
    void theBatchIsPositionallyAlignedWithItsInput() {
        List<String> texts = List.of("alice", "서울", "", "bank");

        List<float[]> vectors = embedder.embedBatch(texts, EmbedPurpose.DOCUMENT);

        assertThat(vectors).hasSameSizeAs(texts);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(vectors.get(i)).isEqualTo(embedder.embed(texts.get(i), EmbedPurpose.DOCUMENT));
        }
    }

    @Test
    void anEmptyBatchIsAnEmptyList() {
        assertThat(embedder.embedBatch(List.of(), EmbedPurpose.DOCUMENT)).isEmpty();
    }
}
