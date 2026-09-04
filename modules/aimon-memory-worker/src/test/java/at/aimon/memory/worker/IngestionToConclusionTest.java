package at.aimon.memory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.config.BatchSettings;
import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.engine.derive.ConclusionWriter;
import at.aimon.memory.engine.derive.DeriverService;
import at.aimon.memory.engine.dream.DreamerService;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.engine.ingest.MessageIngestionService;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.EventLogRepository;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.SessionPeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.testkit.stub.StubEmbedder;
import at.aimon.memory.testkit.stub.StubLlmClient;
import at.aimon.memory.text.AnalyzerRegistry;
import at.aimon.memory.worker.consumer.RepresentationConsumer;

/**
 * The integration gate: a message posted over the write path becomes a stored conclusion, through the
 * real queue, the real claim protocol and the real dedup, with only the model stubbed.
 *
 * <p>Everything below the model is exercised here — sequence allocation, fan-out, batch gating, the
 * claim, the audit log. If this passes, the parts fit together.
 */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class IngestionToConclusionTest {

    private static final String WORKSPACE = "ws";
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String EXTRACTION = """
            {"conclusions":[
              {"content":"alice works at a bank in seoul","entities":["seoul"]},
              {"content":"alice commutes by subway","entities":["subway"]}]}
            """;

    /** No gate is satisfied, so a work unit only becomes ready when a test says so. */
    private static final BatchSettings NEVER = new BatchSettings(1_000_000, Duration.ofDays(365), Duration.ofDays(365));

    private static final BatchSettings IDLE_NOW = new BatchSettings(1_000_000, Duration.ofDays(365),
            Duration.ofSeconds(-1));

    private JdbcClient jdbc;
    private QueueRepository queue;
    private ConclusionRepository conclusions;
    private EventLogRepository events;
    private EntityRepository entities;
    private MessageIngestionService ingestion;
    private RepresentationConsumer consumer;
    private SessionPeerRepository sessionPeers;
    private PeerRepository peers;
    private StubLlmClient llm;
    private final StubEmbedder embedder = new StubEmbedder();

    @BeforeEach
    void wire() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        var workspaces = new WorkspaceRepository(jdbc);
        peers = new PeerRepository(jdbc);
        var sessions = new SessionRepository(jdbc);
        sessionPeers = new SessionPeerRepository(jdbc);
        var messages = new MessageRepository(jdbc);
        var collections = new CollectionRepository(jdbc);
        events = new EventLogRepository(jdbc);
        entities = new EntityRepository(jdbc);
        queue = new QueueRepository(jdbc);

        var settings = new WorkspaceSettingsService(workspaces, new AnalyzerRegistry());
        conclusions = new ConclusionRepository(jdbc, events, collections, settings);
        var entityPipeline = new EntityPipeline(entities, embedder);
        var writer = new ConclusionWriter(conclusions, entityPipeline, embedder, settings);
        llm = StubLlmClient.returning(EXTRACTION);
        var deriver = new DeriverService(llm, writer);
        var dreamer = new DreamerService(llm, conclusions, writer, new DreamRepository(jdbc), collections, CLOCK);

        ingestion = new MessageIngestionService(workspaces, peers, sessions, sessionPeers, messages, collections, queue,
                settings);
        consumer = new RepresentationConsumer(messages, deriver, dreamer, queue);

        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of());
    }

    /** Drain every ready work unit, claiming each one the way the loop does. */
    private void drain(BatchSettings gate) {
        for (var unit : queue.ready(gate, 50)) {
            assertThat(queue.claim(unit.workUnitKey(), "test-worker", Duration.ofMinutes(1))).isTrue();
            try {
                var items = queue.pending(unit.workUnitKey(), 100);
                consumer.consume(WorkUnitKey.parse(unit.workUnitKey()), items);
                queue.markProcessed(items.stream().map(QueueRepository.QueueItem::id).toList());
            } finally {
                queue.release(unit.workUnitKey(), "test-worker");
            }
        }
    }

    @Test
    void aPostedMessageBecomesAStoredConclusion() {
        var result = ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank in Seoul.", Map.of())));

        assertThat(result.messages()).hasSize(1);
        assertThat(result.queued()).hasSize(1);
        assertThat(result.queued().get(0).taskType()).isEqualTo(TaskType.REPRESENTATION);

        // Nothing is ready yet: the batch has not hit a token, age or idle gate.
        assertThat(queue.ready(NEVER, 10)).isEmpty();

        drain(IDLE_NOW);

        PairKey pair = PairKey.self(WORKSPACE, "alice");
        var stored = conclusions.list(pair, Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(c -> c.content()).containsExactlyInAnyOrder("alice works at a bank in seoul",
                "alice commutes by subway");
        assertThat(stored).allSatisfy(c -> {
            assertThat(c.sessionName()).isEqualTo("s1");
            assertThat(c.messageIds()).containsExactly(result.messages().get(0).id());
        });

        // Entities came along at no extra cost, and every write left an event.
        assertThat(entities.findByNorm(WORKSPACE, "seoul")).isPresent();
        assertThat(events.history(WORKSPACE, stored.get(0).id(), 10)).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo(EventType.ADD);
            assertThat(e.actor()).isEqualTo(Actor.DERIVER);
        });

        assertThat(queue.pendingCount(WORKSPACE)).isZero();
        assertThat(ingestion.isDrained(result.queued())).isTrue();
    }

    /**
     * Two observing pairs, two stores — and two extractions, which is the part worth pinning.
     *
     * <p>The specification called for one call fanned out over storage; the implementation asks the
     * model once per pair, because the prompt is written from the observer's side and "what bob may
     * conclude about alice" is not the question alice's own memory answers. ADR 0006 records the
     * decision. This assertion is here so that the cost cannot quietly change in either direction:
     * dropping to one call would mean a pair's memory is no longer its own point of view.
     */
    @Test
    void fanOutWritesToEveryObservingPairAndCostsACallPerPair() {
        peers.getOrCreate(WORKSPACE, "bob", Map.of(), Map.of());
        ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("bob", "hello", Map.of())));
        sessionPeers.join(WORKSPACE, "s1", "bob", true, true);
        // Bob's own greeting has already queued work of its own; drain it so the count below measures
        // only what alice's message costs.
        drain(IDLE_NOW);

        var result = ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));

        assertThat(result.queued()).hasSize(2);
        int before = llm.requests().size();
        drain(IDLE_NOW);

        assertThat(conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).total()).isEqualTo(2);
        assertThat(conclusions.list(new PairKey(WORKSPACE, "bob", "alice"), Filter.ALL, 0, 10).total()).isEqualTo(2);

        // One per observing pair, each carrying that pair's own framing.
        assertThat(llm.requests().subList(before, llm.requests().size())).hasSize(2).extracting(r -> r.system())
                .anySatisfy(system -> assertThat(system).contains("alice's own memory of themselves"))
                .anySatisfy(system -> assertThat(system).contains("bob's memory of alice"));
    }

    /**
     * The batch gate is what keeps one extraction call serving many messages.
     *
     * <p>This is the multiplier that batching does remove. The one it does not is the observer count
     * — see {@link #fanOutWritesToEveryObservingPairAndCostsACallPerPair}.
     */
    @Test
    void aBatchOfMessagesCostsOneExtraction() {
        List<MessageIngestionService.IncomingMessage> batch = List.of(
                new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of()),
                new MessageIngestionService.IncomingMessage("alice", "It is in Seoul.", Map.of()),
                new MessageIngestionService.IncomingMessage("alice", "I take the subway.", Map.of()));

        var result = ingestion.ingest(WORKSPACE, "s1", batch);

        // Three messages, one work unit — they serialise onto the same key.
        assertThat(result.queued()).hasSize(1);
        assertThat(queue.pending(result.queued().get(0).encode(), 10)).hasSize(3);
        assertThat(result.messages()).extracting(m -> m.seqInSession()).containsExactly(1L, 2L, 3L);

        int before = llm.requests().size();
        drain(IDLE_NOW);
        assertThat(llm.requests().size() - before).isEqualTo(1);
        var stored = conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        // Every message in the batch is cited as evidence for every conclusion drawn from it.
        assertThat(stored).allSatisfy(c -> assertThat(c.messageIds()).hasSize(3));
    }

    /** Re-deriving the same batch reinforces rather than duplicating. */
    @Test
    void reprocessingReinforcesInsteadOfDuplicating() {
        var result = ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));
        drain(IDLE_NOW);

        var again = ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));
        drain(IDLE_NOW);

        var stored = conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(c -> assertThat(c.timesDerived()).isEqualTo(2));
        assertThat(result.queued()).isEqualTo(again.queued());
    }

    @Test
    void oversizedBatchesAreRejected() {
        List<MessageIngestionService.IncomingMessage> tooMany = java.util.stream.IntStream
                .range(0, MessageIngestionService.MAX_BATCH + 1)
                .mapToObj(i -> new MessageIngestionService.IncomingMessage("alice", "m" + i, Map.of())).toList();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ingestion.ingest(WORKSPACE, "s1", tooMany)))
                .isInstanceOf(at.aimon.memory.core.MemoryException.class).hasMessageContaining("at most");
    }

    @Test
    void embeddingsAreStoredSoSemanticRecallCanSeeTheRows() {
        ingestion.ingest(WORKSPACE, "s1",
                List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank in Seoul.", Map.of())));
        drain(IDLE_NOW);

        PairKey pair = PairKey.self(WORKSPACE, "alice");
        var hits = conclusions.semantic(pair, embedder.embed("bank seoul", EmbedPurpose.QUERY), 5, Filter.ALL);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).conclusion().content()).contains("bank");
        assertThat(conclusions.pendingEmbedding(10)).isEmpty();
    }

    /**
     * Regression: nothing enqueued the summary work unit, so SummaryConsumer never received work — no
     * session ever got a rolling summary and context() always returned an empty one, silently.
     */
    @Test
    void ingestingAlsoQueuesTheSessionSummary() {
        ingestion.ingest(WORKSPACE, "s1", List.of(new MessageIngestionService.IncomingMessage("alice", "hello", null)));

        assertThat(queue.pending(WorkUnitKey.summary(WORKSPACE, "s1").encode(), 10))
                .as("a summary trigger is queued for the session").isNotEmpty();
    }

}
