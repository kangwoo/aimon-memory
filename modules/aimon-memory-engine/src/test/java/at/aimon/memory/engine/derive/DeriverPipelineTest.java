package at.aimon.memory.engine.derive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.engine.MemoryTestBase;
import at.aimon.memory.store.repo.MessageRepository;

/** The write path end to end, with the model stubbed: extraction to rows, entities and events. */
class DeriverPipelineTest extends MemoryTestBase {

    private static final String TWO_CONCLUSIONS = """
            {"conclusions":[
              {"content":"alice works at a bank in seoul","entities":["seoul","alice"]},
              {"content":"alice prefers morning meetings","entities":["alice"]}]}
            """;

    private List<Message> seedMessages(String session, String peer, String... contents) {
        seedSession(session);
        peers.getOrCreate(WORKSPACE, peer, Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, session, contents.length);
        List<MessageRepository.NewMessage> rows = java.util.Arrays.stream(contents)
                .map(c -> new MessageRepository.NewMessage(peer, c, 5, Map.of())).toList();
        return messages.insertBatch(WORKSPACE, session, start, rows);
    }

    @Test
    void extractionBecomesConclusionsEntitiesAndEvents() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank in Seoul.");

        var result = deriverReturning(TWO_CONCLUSIONS).deriveAndWrite(pair, "s1", batch);

        assertThat(result.inserted()).isEqualTo(2);
        var stored = conclusions.list(pair, at.aimon.memory.core.filter.Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(c -> assertThat(c.messageIds()).containsExactly(batch.get(0).id()));

        // Entities came out of the same call, at no extra cost.
        assertThat(entities.findByNorm(WORKSPACE, "seoul")).isPresent();
        assertThat(entities.findByNorm(WORKSPACE, "alice")).isPresent();

        String bankId = stored.stream().filter(c -> c.content().contains("bank")).findFirst().orElseThrow().id();
        assertThat(entities.entitiesFor(WORKSPACE, bankId)).extracting(e -> e.nameNorm())
                .containsExactlyInAnyOrder("seoul", "alice");

        assertThat(events.history(WORKSPACE, bankId, 10)).singleElement()
                .satisfies(e -> assertThat(e.event()).isEqualTo(EventType.ADD));
    }

    /**
     * One extraction, many pairs. Extracting per observer would multiply the cost of a group
     * conversation by its size to produce the same sentences.
     */
    @Test
    void oneExtractionFansOutToEveryObservingPair() {
        PairKey self = seedPair("alice", "alice");
        PairKey observer = seedPair("bob", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank in Seoul.");

        DeriverService deriver = deriverReturning(TWO_CONCLUSIONS);
        var derived = deriver.derive(self, batch);
        deriver.write(self, "s1", derived, batch);
        deriver.write(observer, "s1", derived, batch);

        assertThat(conclusions.list(self, at.aimon.memory.core.filter.Filter.ALL, 0, 10).total()).isEqualTo(2);
        assertThat(conclusions.list(observer, at.aimon.memory.core.filter.Filter.ALL, 0, 10).total()).isEqualTo(2);
    }

    /** The observer and observed have to reach the prompt, or every pair gets the same memory. */
    @Test
    void thePromptCarriesThePairPerspective() {
        PairKey observer = seedPair("bob", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank.");

        var stub = new at.aimon.memory.testkit.stub.StubLlmClient(request -> TWO_CONCLUSIONS);
        new DeriverService(stub, writer).derive(observer, batch);

        assertThat(stub.lastRequest().system()).contains("bob").contains("alice");
        assertThat(stub.lastRequest().messages().get(0).content())
                .matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} alice: I work at a bank\\.");
    }

    /**
     * Entity quality is downstream of the extraction prompt — that is what getting entities for free
     * costs. Recording which prompt produced a fact is what makes a re-index targetable later.
     */
    @Test
    void theAuditTrailNamesThePromptThatProducedTheFact() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank in Seoul.");
        deriverReturning(TWO_CONCLUSIONS).deriveAndWrite(pair, "s1", batch);

        var stored = conclusions.list(pair, at.aimon.memory.core.filter.Filter.ALL, 0, 10).items();
        assertThat(events.history(WORKSPACE, stored.get(0).id(), 10)).singleElement()
                .satisfies(e -> assertThat(e.detail())
                        .containsEntry("prompt_version", at.aimon.memory.engine.prompt.Prompts.VERSION)
                        .containsEntry("level", "explicit"));
    }

    /**
     * Regression: the orphan sweep ran before the new links were written, so a replacement destroyed
     * the entity node it was about to re-attach and then recreated it under a new id, paying for the
     * embedding again to reach the same state.
     */
    @Test
    void replacingAConclusionKeepsTheEntityNodeItReattaches() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank in Seoul.");

        deriverReturning("{\"conclusions\":[{\"content\":\"alice works at a bank in seoul gangnam district"
                + " near the han river every weekday morning\",\"entities\":[\"seoul\"]}]}")
                .deriveAndWrite(pair, "s1", batch);
        String entityIdBefore = entities.findByNorm(WORKSPACE, "seoul").orElseThrow().id();

        // Same fact, more detail: dedup stage 3 replaces, and the entity moves to the surviving row.
        deriverReturning("{\"conclusions\":[{\"content\":\"alice works at a bank in seoul gangnam district"
                + " near the han river every weekday morning promptly\",\"entities\":[\"seoul\"]}]}")
                .deriveAndWrite(pair, "s1", batch);

        assertThat(entities.findByNorm(WORKSPACE, "seoul")).isPresent();
        assertThat(entities.findByNorm(WORKSPACE, "seoul").orElseThrow().id())
                .as("the node survives the replacement rather than being deleted and rebuilt")
                .isEqualTo(entityIdBefore);

        var live = conclusions.list(pair, at.aimon.memory.core.filter.Filter.ALL, 0, 10).items();
        assertThat(live).hasSize(1);
        assertThat(entities.entitiesFor(WORKSPACE, live.get(0).id())).hasSize(1);
    }

    @Test
    void anEmptyExtractionWritesNothing() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "hi");

        var result = deriverReturning("{\"conclusions\":[]}").deriveAndWrite(pair, "s1", batch);

        assertThat(result.outcomes()).isEmpty();
        assertThat(conclusions.list(pair, at.aimon.memory.core.filter.Filter.ALL, 0, 10).total()).isZero();
    }

    @Test
    void blankConclusionsAreDiscarded() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "hi");

        var result = deriverReturning("{\"conclusions\":[{\"content\":\"   \",\"entities\":[]}]}").deriveAndWrite(pair,
                "s1", batch);
        assertThat(result.outcomes()).isEmpty();
    }

    /** After a replace, the entities belong to the surviving row, not the id that was proposed. */
    @Test
    void entityEdgesFollowTheRowThatSurvivesDedup() {
        PairKey pair = seedPair("alice", "alice");
        List<Message> batch = seedMessages("s1", "alice", "I work at a bank in Seoul.");
        DeriverService deriver = deriverReturning(TWO_CONCLUSIONS);
        deriver.deriveAndWrite(pair, "s1", batch);

        // The same extraction again: dedup reinforces, and the edges must not be duplicated or orphaned.
        deriver.deriveAndWrite(pair, "s1", batch);

        var stored = conclusions.list(pair, at.aimon.memory.core.filter.Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(c -> assertThat(c.timesDerived()).isEqualTo(2));

        String bankId = stored.stream().filter(c -> c.content().contains("bank")).findFirst().orElseThrow().id();
        assertThat(entities.entitiesFor(WORKSPACE, bankId)).hasSize(2);
    }
}
