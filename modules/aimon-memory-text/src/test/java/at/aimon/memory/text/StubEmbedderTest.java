package at.aimon.memory.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.testkit.stub.StubEmbedder;

/**
 * The stand-in embedders have to be wrong in an honest direction.
 *
 * <p>They are lexical, and that is fine and documented. What is not fine is a tokenisation that makes
 * two Korean texts about the same place look completely unrelated — a test would then show a
 * similarity of zero and read as a bug in the ranker rather than a limit of the stub.
 */
class StubEmbedderTest {

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    @Test
    void koreanTextsSharingAPlaceNameAreRelated() {
        StubEmbedder embedder = new StubEmbedder();
        float[] withParticle = embedder.embed("강남의 은행", EmbedPurpose.DOCUMENT);
        float[] bare = embedder.embed("강남 은행", EmbedPurpose.QUERY);

        // A whitespace split gives these nothing in common: 강남의 and 강남 are simply different words.
        assertThat(cosine(withParticle, bare)).isGreaterThan(0.5);

        StubEmbedder whitespace = new StubEmbedder(1536, new StubAnalyzer());
        assertThat(cosine(whitespace.embed("강남의 은행", EmbedPurpose.DOCUMENT),
                whitespace.embed("강남 은행", EmbedPurpose.QUERY)))
                .as("the old behaviour, kept available for tests that want it").isLessThan(0.6);
    }

    @Test
    void unrelatedKoreanTextsStayUnrelated() {
        StubEmbedder embedder = new StubEmbedder();
        assertThat(cosine(embedder.embed("강남 은행", EmbedPurpose.DOCUMENT), embedder.embed("제주 등산", EmbedPurpose.QUERY)))
                .isLessThan(0.2);
    }

    @Test
    void englishBehaviourIsUnchanged() {
        StubEmbedder embedder = new StubEmbedder();
        float[] first = embedder.embed("alice works at a bank", EmbedPurpose.DOCUMENT);
        float[] second = embedder.embed("alice works at a bank", EmbedPurpose.QUERY);
        assertThat(cosine(first, second)).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-5));
    }

    @Test
    void embeddingIsDeterministic() {
        assertThat(new StubEmbedder().embed("서울 강남", EmbedPurpose.DOCUMENT))
                .isEqualTo(new StubEmbedder().embed("서울 강남", EmbedPurpose.DOCUMENT));
    }
}
