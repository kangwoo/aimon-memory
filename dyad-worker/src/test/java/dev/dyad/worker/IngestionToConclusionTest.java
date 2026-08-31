package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.config.BatchSettings;
import dev.dyad.core.filter.Filter;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.TaskType;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.EventType;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.memory.derive.ConclusionWriter;
import dev.dyad.memory.derive.DeriverService;
import dev.dyad.memory.dream.DreamerService;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.memory.ingest.MessageIngestionService;
import dev.dyad.store.WorkspaceSettingsService;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.DreamRepository;
import dev.dyad.store.repo.EntityRepository;
import dev.dyad.store.repo.EventLogRepository;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.SessionPeerRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import dev.dyad.testkit.stub.StubEmbedder;
import dev.dyad.testkit.stub.StubLlmClient;
import dev.dyad.text.AnalyzerRegistry;
import dev.dyad.worker.consumer.RepresentationConsumer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The integration gate: a message posted over the write path becomes a stored conclusion, through the
 * real queue, the real claim protocol and the real dedup, with only the model stubbed.
 *
 * <p>Everything below the model is exercised here — sequence allocation, fan-out, batch gating, the
 * claim, the audit log. If this passes, the parts fit together.
 */
class IngestionToConclusionTest {

    private static final String WORKSPACE = "ws";
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String EXTRACTION =
            """
            {"conclusions":[
              {"content":"alice works at a bank in seoul","entities":["seoul"]},
              {"content":"alice commutes by subway","entities":["subway"]}]}
            """;

    /** No gate is satisfied, so a work unit only becomes ready when a test says so. */
    private static final BatchSettings NEVER =
            new BatchSettings(1_000_000, Duration.ofDays(365), Duration.ofDays(365));

    private static final BatchSettings IDLE_NOW =
            new BatchSettings(1_000_000, Duration.ofDays(365), Duration.ofSeconds(-1));

    private JdbcClient jdbc;
    private QueueRepository queue;
    private ConclusionRepository conclusions;
    private EventLogRepository events;
    private EntityRepository entities;
    private MessageIngestionService ingestion;
    private RepresentationConsumer consumer;
    private SessionPeerRepository sessionPeers;
    private PeerRepository peers;
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
        var llm = StubLlmClient.returning(EXTRACTION);
        var deriver = new DeriverService(llm, writer);
        var dreamer =
                new DreamerService(llm, conclusions, writer, new DreamRepository(jdbc), collections, CLOCK);

        ingestion =
                new MessageIngestionService(
                        workspaces, peers, sessions, sessionPeers, messages, collections, queue, settings);
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
        var result =
                ingestion.ingest(
                        WORKSPACE,
                        "s1",
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
        assertThat(stored).extracting(c -> c.content())
                .containsExactlyInAnyOrder("alice works at a bank in seoul", "alice commutes by subway");
        assertThat(stored).allSatisfy(c -> {
            assertThat(c.sessionName()).isEqualTo("s1");
            assertThat(c.messageIds()).containsExactly(result.messages().get(0).id());
        });

        // Entities came along at no extra cost, and every write left an event.
        assertThat(entities.findByNorm(WORKSPACE, "seoul")).isPresent();
        assertThat(events.history(WORKSPACE, stored.get(0).id(), 10))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.event()).isEqualTo(EventType.ADD);
                    assertThat(e.actor()).isEqualTo(Actor.DERIVER);
                });

        assertThat(queue.pendingCount(WORKSPACE)).isZero();
        assertThat(ingestion.isDrained(result.queued())).isTrue();
    }

    /** One extraction, two stores: the speaker's own memory and the listener's memory of them. */
    @Test
    void fanOutWritesToEveryObservingPair() {
        peers.getOrCreate(WORKSPACE, "bob", Map.of(), Map.of());
        ingestion.ingest(WORKSPACE, "s1", List.of(
                new MessageIngestionService.IncomingMessage("bob", "hello", Map.of())));
        sessionPeers.join(WORKSPACE, "s1", "bob", true, true);

        var result =
                ingestion.ingest(
                        WORKSPACE,
                        "s1",
                        List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));

        assertThat(result.queued()).hasSize(2);
        drain(IDLE_NOW);

        assertThat(conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).total()).isEqualTo(2);
        assertThat(conclusions.list(new PairKey(WORKSPACE, "bob", "alice"), Filter.ALL, 0, 10).total())
                .isEqualTo(2);
    }

    /** The batch gate is what keeps one extraction call serving many messages. */
    @Test
    void aBatchOfMessagesCostsOneExtraction() {
        List<MessageIngestionService.IncomingMessage> batch =
                List.of(
                        new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of()),
                        new MessageIngestionService.IncomingMessage("alice", "It is in Seoul.", Map.of()),
                        new MessageIngestionService.IncomingMessage("alice", "I take the subway.", Map.of()));

        var result = ingestion.ingest(WORKSPACE, "s1", batch);

        // Three messages, one work unit — they serialise onto the same key.
        assertThat(result.queued()).hasSize(1);
        assertThat(queue.pending(result.queued().get(0).encode(), 10)).hasSize(3);
        assertThat(result.messages()).extracting(m -> m.seqInSession()).containsExactly(1L, 2L, 3L);

        drain(IDLE_NOW);
        var stored = conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        // Every message in the batch is cited as evidence for every conclusion drawn from it.
        assertThat(stored).allSatisfy(c -> assertThat(c.messageIds()).hasSize(3));
    }

    /** Re-deriving the same batch reinforces rather than duplicating. */
    @Test
    void reprocessingReinforcesInsteadOfDuplicating() {
        var result =
                ingestion.ingest(
                        WORKSPACE, "s1",
                        List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));
        drain(IDLE_NOW);

        var again =
                ingestion.ingest(
                        WORKSPACE, "s1",
                        List.of(new MessageIngestionService.IncomingMessage("alice", "I work at a bank.", Map.of())));
        drain(IDLE_NOW);

        var stored = conclusions.list(PairKey.self(WORKSPACE, "alice"), Filter.ALL, 0, 10).items();
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(c -> assertThat(c.timesDerived()).isEqualTo(2));
        assertThat(result.queued()).isEqualTo(again.queued());
    }

    @Test
    void oversizedBatchesAreRejected() {
        List<MessageIngestionService.IncomingMessage> tooMany =
                java.util.stream.IntStream.range(0, MessageIngestionService.MAX_BATCH + 1)
                        .mapToObj(i -> new MessageIngestionService.IncomingMessage("alice", "m" + i, Map.of()))
                        .toList();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ingestion.ingest(WORKSPACE, "s1", tooMany)))
                .isInstanceOf(dev.dyad.core.DyadException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void embeddingsAreStoredSoSemanticRecallCanSeeTheRows() {
        ingestion.ingest(
                WORKSPACE, "s1",
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
        ingestion.ingest(
                WORKSPACE, "s1", List.of(new MessageIngestionService.IncomingMessage("alice", "hello", null)));

        assertThat(queue.pending(WorkUnitKey.summary(WORKSPACE, "s1").encode(), 10))
                .as("a summary trigger is queued for the session")
                .isNotEmpty();
    }

}
