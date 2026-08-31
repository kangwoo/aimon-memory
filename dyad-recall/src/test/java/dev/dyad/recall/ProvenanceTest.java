package dev.dyad.recall;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.text.ContentHash;
import dev.dyad.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The chain neither source design can walk: entity to conclusion to premise to original message,
 * with no model in the loop.
 */
class ProvenanceTest extends RecallTestBase {

    private long seedMessage(String session, String peer, String content) {
        long start = sessions.nextSequence(WORKSPACE, session, 1);
        return messages
                .insertBatch(
                        WORKSPACE, session, start,
                        List.of(new MessageRepository.NewMessage(peer, content, 5, Map.of())))
                .get(0)
                .id();
    }

    private String storeWithSources(
            PairKey pair, String content, List<String> sourceIds, List<Long> messageIds, ConclusionLevel level) {
        String norm = Normalizer.normalize(content);
        return conclusions
                .upsert(
                        ConclusionDraft.builder()
                                .pair(pair)
                                .sessionName(level == ConclusionLevel.EXPLICIT ? "s1" : null)
                                .content(content)
                                .contentNorm(norm)
                                .contentAnalyzed(analyzer.analyze(content))
                                .contentHash(ContentHash.of(norm))
                                .level(level)
                                .sourceIds(sourceIds)
                                .messageIds(messageIds)
                                .embedding(embedder.embed(content, EmbedPurpose.DOCUMENT))
                                .actor(Actor.DREAMER)
                                .build())
                .conclusionId();
    }

    @Test
    void entityWalksThroughToTheOriginalMessages() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long messageId = seedMessage("s1", "alice", "I work at a bank in Gangnam, Seoul.");

        String premise =
                storeWithSources(pair, "alice works at a bank in seoul", List.of(), List.of(messageId),
                        ConclusionLevel.EXPLICIT);
        linkEntity(pair, premise, "seoul");

        var result = provenance.forEntity(pair, "서울".equals("x") ? "x" : "seoul", 10).orElseThrow();

        assertThat(result.entity().nameDisplay()).isEqualTo("seoul");
        assertThat(result.conclusions()).singleElement().satisfies(entry -> {
            assertThat(entry.conclusion().id()).isEqualTo(premise);
            assertThat(entry.sourceMessages()).singleElement().satisfies(
                    message -> assertThat(message.content()).contains("Gangnam"));
        });
    }

    /**
     * A deduction was never said out loud, so its evidence is the text behind its premises. Walking
     * only its own message ids would report no evidence at all for exactly the conclusions that most
     * need justifying.
     */
    @Test
    void deductionsInheritTheirEvidenceFromTheirPremises() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long lives = seedMessage("s1", "alice", "I live in Busan.");
        long works = seedMessage("s1", "alice", "My office is in Busan too.");

        String premiseA = storeWithSources(pair, "alice lives in busan", List.of(), List.of(lives), ConclusionLevel.EXPLICIT);
        String premiseB = storeWithSources(pair, "alice works in busan", List.of(), List.of(works), ConclusionLevel.EXPLICIT);
        String deduction =
                storeWithSources(
                        pair, "alice does not commute between cities", List.of(premiseA, premiseB), List.of(),
                        ConclusionLevel.DEDUCTIVE);

        var trace = provenance.forConclusion(pair, conclusions.find(WORKSPACE, deduction).orElseThrow());

        assertThat(trace.premises()).extracting(c -> c.id()).containsExactlyInAnyOrder(premiseA, premiseB);
        assertThat(trace.sourceMessages()).extracting(m -> m.id()).containsExactlyInAnyOrder(lives, works);
    }

    /**
     * Regression pair. A deleted premise used to come back through the chain looking live, because
     * byIds ignored deleted_at and the response carries no deleted marker. Now it drops out — and the
     * gap is named, so a shortened chain is visibly shortened rather than looking complete.
     */
    @Test
    void aDeletedPremiseLeavesTheChainAndIsReportedAsMissing() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long said = seedMessage("s1", "alice", "I live in Busan.");

        String premiseA = storeWithSources(pair, "alice lives in busan", List.of(), List.of(said), ConclusionLevel.EXPLICIT);
        String premiseB = storeWithSources(pair, "alice works in busan", List.of(), List.of(), ConclusionLevel.EXPLICIT);
        String deduction =
                storeWithSources(pair, "alice does not commute", List.of(premiseA, premiseB), List.of(),
                        ConclusionLevel.DEDUCTIVE);

        conclusions.softDelete(
                WORKSPACE, premiseB, dev.dyad.core.model.Actor.API, Map.of(),
                dev.dyad.core.model.EventType.DELETE);

        var trace = provenance.forConclusion(pair, conclusions.find(WORKSPACE, deduction).orElseThrow());

        assertThat(trace.premises()).extracting(c -> c.id()).containsExactly(premiseA);
        assertThat(trace.unresolvedPremiseIds()).containsExactly(premiseB);
    }

    @Test
    void aDeletedConclusionIsNotReturnedAsEntityProvenance() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String conclusionId =
                storeWithSources(pair, "alice lives in seoul", List.of(), List.of(), ConclusionLevel.EXPLICIT);
        linkEntity(pair, conclusionId, "seoul");
        assertThat(provenance.forEntity(pair, "seoul", 10).orElseThrow().conclusions()).hasSize(1);

        conclusions.softDelete(
                WORKSPACE, conclusionId, dev.dyad.core.model.Actor.API, Map.of(),
                dev.dyad.core.model.EventType.DELETE);

        assertThat(provenance.forEntity(pair, "seoul", 10).orElseThrow().conclusions()).isEmpty();
    }

    @Test
    void theTreeIsWalkableInBothDirections() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String premise = storeWithSources(pair, "alice lives in busan", List.of(), List.of(), ConclusionLevel.EXPLICIT);
        String derived =
                storeWithSources(pair, "alice is based in the south", List.of(premise), List.of(),
                        ConclusionLevel.DEDUCTIVE);

        assertThat(provenance.dependents(WORKSPACE, premise)).extracting(c -> c.id()).containsExactly(derived);
        assertThat(provenance.dependents(WORKSPACE, derived)).isEmpty();
    }

    /** A cycle in source_ids must terminate rather than produce an infinite response. */
    @Test
    void aCycleInTheTreeTerminates() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String a = storeWithSources(pair, "statement a", List.of(), List.of(), ConclusionLevel.DEDUCTIVE);
        String b = storeWithSources(pair, "statement b", List.of(a), List.of(), ConclusionLevel.DEDUCTIVE);
        jdbc.sql("UPDATE conclusions SET source_ids = ?::jsonb WHERE id = ?")
                .params("[\"" + b + "\"]", a)
                .update();

        var trace = provenance.forConclusion(pair, conclusions.find(WORKSPACE, b).orElseThrow());

        // Terminates, and the root does not reappear as its own premise.
        assertThat(trace.premises()).extracting(c -> c.id()).containsExactly(a);
    }

    @Test
    void anUnknownEntityIsAbsentRatherThanEmpty() {
        PairKey pair = seedPair("alice", "alice");
        assertThat(provenance.forEntity(pair, "nowhere", 10)).isEmpty();
    }

    /** Pair isolation holds through the provenance walk, not only through recall. */
    @Test
    void provenanceIsPairScoped() {
        PairKey mine = seedPair("alice", "alice");
        PairKey theirs = seedPair("bob", "alice");
        seedSession("s1");

        String conclusionId = storeWithSources(mine, "alice lives in seoul", List.of(), List.of(), ConclusionLevel.EXPLICIT);
        linkEntity(mine, conclusionId, "seoul");

        assertThat(provenance.forEntity(theirs, "seoul", 10).orElseThrow().conclusions()).isEmpty();
    }
}
