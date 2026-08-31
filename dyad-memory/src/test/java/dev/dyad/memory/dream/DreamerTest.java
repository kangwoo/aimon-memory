package dev.dyad.memory.dream;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.Conclusion;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.memory.MemoryTestBase;
import dev.dyad.store.repo.DreamRepository;
import dev.dyad.testkit.stub.StubAnalyzer;
import dev.dyad.testkit.stub.StubLlmClient;
import dev.dyad.text.ContentHash;
import dev.dyad.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class DreamerTest extends MemoryTestBase {

    private final StubAnalyzer analyzer = new StubAnalyzer();

    private String store(PairKey pair, String content) {
        String norm = Normalizer.normalize(content);
        return conclusions
                .upsert(
                        ConclusionDraft.builder()
                                .pair(pair)
                                .sessionName("s1")
                                .content(content)
                                .contentNorm(norm)
                                .contentAnalyzed(analyzer.analyze(content))
                                .contentHash(ContentHash.of(norm))
                                .level(ConclusionLevel.EXPLICIT)
                                .embedding(embedder.embed(content, EmbedPurpose.DOCUMENT))
                                .actor(Actor.DERIVER)
                                .build())
                .conclusionId();
    }

    private PairKey seedFacts(int count) {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        for (int i = 0; i < count; i++) {
            store(pair, "alice fact number " + i + " about topic " + i);
        }
        return pair;
    }

    private DreamerService dreamer(StubLlmClient stub) {
        return new DreamerService(stub, conclusions, writer, dreams, collections, CLOCK);
    }

    @Test
    void schedulingWaitsForEnoughNewMaterial() {
        PairKey pair = seedFacts(DreamerService.EXPLICIT_THRESHOLD - 1);
        assertThat(dreamer(StubLlmClient.returning("{}")).scheduleIfDue(pair)).isEmpty();

        store(pair, "alice one more fact to cross the line");
        assertThat(dreamer(StubLlmClient.returning("{}")).scheduleIfDue(pair)).isPresent();
    }

    /**
     * The one-in-flight rule is a partial unique index, not a check-then-insert. The manual trigger
     * and the automatic scheduler race constantly, and the loser has to learn it from a constraint.
     */
    @Test
    void onlyOneDreamCanBeInFlightPerPair() {
        PairKey pair = seedFacts(DreamerService.EXPLICIT_THRESHOLD);
        DreamerService service = dreamer(StubLlmClient.returning("{}"));

        assertThat(service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE)).isPresent();
        assertThat(service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE)).isEmpty();
    }

    @Test
    void aFinishedDreamFreesThePairForTheNextOne() {
        PairKey pair = seedFacts(DreamerService.EXPLICIT_THRESHOLD);
        DreamerService service = dreamer(StubLlmClient.returning("{}"));

        var first = service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        dreams.complete(first.id(), 0);
        assertThat(service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE)).isPresent();
    }

    @Test
    void derivedConclusionsRecordTheirPremises() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String lives = store(pair, "alice lives in busan");
        String works = store(pair, "alice works in busan");

        String response =
                """
                {"conclusions":[{"content":"alice does not commute between cities",
                  "sourceIds":["%s","%s"],"entities":["busan"],"confidence":1.0}]}
                """
                        .formatted(lives, works);

        DreamerService service = dreamer(StubLlmClient.returning(response));
        var dream = service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        service.run(dream);

        List<Conclusion> derived =
                conclusions.list(pair, Filter.ALL, 0, 50).items().stream()
                        .filter(c -> c.level() != ConclusionLevel.EXPLICIT)
                        .toList();

        assertThat(derived).isNotEmpty();
        assertThat(derived).allSatisfy(c -> assertThat(c.sourceIds()).containsExactlyInAnyOrder(lives, works));
        assertThat(derived).anySatisfy(c -> assertThat(c.level()).isEqualTo(ConclusionLevel.DEDUCTIVE));

        // Autonomous edits are the reason the audit log exists.
        assertThat(events.history(WORKSPACE, derived.get(0).id(), 10))
                .allSatisfy(e -> assertThat(e.actor()).isEqualTo(Actor.DREAMER));
        assertThat(dreams.find(dream.id()).orElseThrow().status()).isEqualTo("completed");
    }

    /**
     * A source id the model invented is dropped, taking the conclusion with it. A dangling premise
     * makes the reasoning chain lie, and a provenance trail that lies is worse than none.
     */
    @Test
    void conclusionsCitingUnknownPremisesAreDiscarded() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        store(pair, "alice lives in busan");

        String response =
                """
                {"conclusions":[{"content":"alice invented something",
                  "sourceIds":["totally-made-up-id"],"entities":[],"confidence":0.9}]}
                """;

        DreamerService service = dreamer(StubLlmClient.returning(response));
        var dream = service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        service.run(dream);

        assertThat(conclusions.list(pair, Filter.ALL, 0, 50).items())
                .allSatisfy(c -> assertThat(c.level()).isEqualTo(ConclusionLevel.EXPLICIT));
        assertThat(dreams.find(dream.id()).orElseThrow().produced()).isZero();
    }

    /** Patterns go stale, so induction gets a lifetime. A deduction does not. */
    @Test
    void inductiveConclusionsCarryConfidenceAndATtl() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String fact = store(pair, "alice drinks coffee every morning");

        String response =
                """
                {"conclusions":[{"content":"alice keeps a fixed morning routine",
                  "sourceIds":["%s"],"entities":[],"confidence":0.4}]}
                """
                        .formatted(fact);

        DreamerService service = dreamer(StubLlmClient.returning(response));
        var dream = service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        service.run(dream);

        var inductive =
                conclusions.list(pair, Filter.ALL, 0, 50).items().stream()
                        .filter(c -> c.level() == ConclusionLevel.INDUCTIVE)
                        .findFirst()
                        .orElseThrow();

        // The column is REAL, so the comparison carries float precision rather than double.
        assertThat(inductive.confidence()).isCloseTo(0.4, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(inductive.expiresAt()).isNotNull().isAfter(NOW);
        assertThat(inductive.sessionName()).as("derived conclusions span the pair, not a session").isNull();

        var deductive =
                conclusions.list(pair, Filter.ALL, 0, 50).items().stream()
                        .filter(c -> c.level() == ConclusionLevel.DEDUCTIVE)
                        .findFirst()
                        .orElseThrow();
        assertThat(deductive.expiresAt()).isNull();
    }

    @Test
    void aFailingDreamIsRecordedAsFailed() {
        PairKey pair = seedFacts(3);
        DreamerService service = dreamer(StubLlmClient.returning("not json at all"));
        var dream = service.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.run(dream))).isNotNull();
        assertThat(dreams.find(dream.id()).orElseThrow().status()).isEqualTo("failed");
    }
}
