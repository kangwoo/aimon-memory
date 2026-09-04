package at.aimon.memory.engine.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.engine.MemoryTestBase;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.testkit.stub.StubLlmClient;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.Normalizer;

class PeerCardServiceTest extends MemoryTestBase {

    private final StubAnalyzer analyzer = new StubAnalyzer();
    private PairKey pair;

    @BeforeEach
    void seedFacts() {
        pair = seedPair("bob", "alice");
        seedSession("s1");
        store("alice works at a bank in seoul");
        store("alice prefers short answers");
    }

    private void store(String content) {
        String norm = Normalizer.normalize(content);
        conclusions.upsert(ConclusionDraft.builder().pair(pair).sessionName("s1").content(content).contentNorm(norm)
                .contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm))
                .level(ConclusionLevel.EXPLICIT).embedding(embedder.embed(content, EmbedPurpose.DOCUMENT))
                .actor(Actor.DERIVER).build());
    }

    private PeerCardService service(String json) {
        return new PeerCardService(StubLlmClient.returning(json), conclusions, cards);
    }

    @Test
    void aGoodGenerationReplacesTheCardWholesale() {
        service("{\"lines\":[\"IDENTITY: alice\",\"ATTRIBUTE: works at a bank\"]}").refresh(pair);
        assertThat(cards.find(pair).orElseThrow().lines()).containsExactly("IDENTITY: alice",
                "ATTRIBUTE: works at a bank");

        service("{\"lines\":[\"IDENTITY: alice, engineer\"]}").refresh(pair);
        // Whole replacement: anything worth keeping has to be written again.
        assertThat(cards.find(pair).orElseThrow().lines()).containsExactly("IDENTITY: alice, engineer");
    }

    /**
     * Regression: validation dropped every unprefixed line and the empty result was written straight
     * over the stored card, so one malformed generation destroyed a good profile.
     */
    @Test
    void aMalformedGenerationLeavesTheExistingCardAlone() {
        service("{\"lines\":[\"IDENTITY: alice\"]}").refresh(pair);

        assertThatThrownBy(() -> service("{\"lines\":[\"she seems nice\",\"works somewhere\"]}").refresh(pair))
                .isInstanceOf(MemoryException.class).hasMessageContaining("previous card is unchanged");

        assertThat(cards.find(pair).orElseThrow().lines()).containsExactly("IDENTITY: alice");
    }

    @Test
    void aPairWithNothingKnownProducesNoCardAndNoCall() {
        PairKey empty = seedPair("bob", "carol");
        assertThat(service("{\"lines\":[]}").refresh(empty)).isEmpty();
        assertThat(cards.find(empty)).isEmpty();
    }

    @Test
    void refreshingDoesNotAdvanceTheDreamSchedulingState() {
        service("{\"lines\":[\"IDENTITY: alice\"]}").refresh(pair);
        // A card refresh must not consume the budget meant for the pass that produces new knowledge.
        assertThat(collections.dreamState(pair).orElseThrow().lastDreamAt()).isNull();
        assertThat(List.of(collections.dreamState(pair).orElseThrow().explicitAtLastDream())).containsExactly(0);
    }
}
