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

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.EventLogRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.testkit.stub.StubEmbedder;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.Normalizer;

/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class ReconcilerTest {

    private static final String WORKSPACE = "ws";
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private JdbcClient jdbc;
    private ConclusionRepository conclusions;
    private EventLogRepository events;
    private QueueRepository queue;
    private ReconcilerService reconciler;
    private WorkspaceRepository workspaces;
    private EntityRepository entities;
    private DreamRepository dreams;
    private PairKey pair;
    private final StubEmbedder embedder = new StubEmbedder();
    private final StubAnalyzer analyzer = new StubAnalyzer();

    @BeforeEach
    void wire() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        workspaces = new WorkspaceRepository(jdbc);
        var peers = new PeerRepository(jdbc);
        var sessions = new SessionRepository(jdbc);
        var collections = new CollectionRepository(jdbc);
        events = new EventLogRepository(jdbc);
        conclusions = new ConclusionRepository(jdbc, events, collections,
                new at.aimon.memory.store.WorkspaceSettingsService(workspaces,
                        new at.aimon.memory.text.AnalyzerRegistry()));
        queue = new QueueRepository(jdbc);
        entities = new EntityRepository(jdbc);
        dreams = new DreamRepository(jdbc);
        reconciler = new ReconcilerService(conclusions, queue, workspaces, new EntityPipeline(entities, embedder),
                dreams, embedder, Clock.fixed(NOW, ZoneOffset.UTC));

        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        sessions.getOrCreate(WORKSPACE, "s1", Map.of(), Map.of());
        pair = PairKey.self(WORKSPACE, "alice");
        collections.getOrCreate(pair);
    }

    @SuppressWarnings("UnusedReturnValue")
    private String store(String content, float[] embedding, Instant expiresAt) {
        String norm = Normalizer.normalize(content);
        return conclusions.upsert(ConclusionDraft.builder().pair(pair).sessionName("s1").content(content)
                .contentNorm(norm).contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm))
                .level(ConclusionLevel.EXPLICIT).embedding(embedding).expiresAt(expiresAt).actor(Actor.DERIVER).build())
                .conclusionId();
    }

    /**
     * A conclusion whose embedding call failed is invisible to semantic recall with no error anywhere.
     * The sweep is what makes that condition temporary rather than permanent.
     */
    @Test
    void backfillsMissingEmbeddings() {
        String id = store("alice works at a bank", null, null);
        assertThat(conclusions.pendingEmbedding(10)).extracting(c -> c.id()).contains(id);

        assertThat(reconciler.syncEmbeddings()).isEqualTo(1);
        assertThat(conclusions.pendingEmbedding(10)).isEmpty();
        assertThat(conclusions.semantic(pair, embedder.embed("bank", EmbedPurpose.QUERY), 5, Filter.ALL)).isNotEmpty();
    }

    /** Expiry is the system's own decision, so it is logged as EXPIRE rather than DELETE. */
    @Test
    void expiredRowsAreSoftDeletedAndLoggedAsExpiry() {
        String id = store("alice is on holiday this week", embedder.embed("holiday", EmbedPurpose.DOCUMENT),
                NOW.minus(Duration.ofDays(1)));

        assertThat(reconciler.expire()).isEqualTo(1);
        assertThat(conclusions.find(WORKSPACE, id).orElseThrow().isDeleted()).isTrue();
        assertThat(events.history(WORKSPACE, id, 10)).anySatisfy(e -> {
            assertThat(e.event()).isEqualTo(EventType.EXPIRE);
            assertThat(e.actor()).isEqualTo(Actor.RECONCILER);
        });
    }

    /**
     * Regression: expiry soft-deleted the row but left its entity edges, so an expired fact kept
     * inflating entity link counts and kept its node alive through the orphan sweep. The explicit
     * delete path did clean up, which made the two paths disagree.
     */
    @Test
    void expiryCleansUpEntityEdgesLikeAnExplicitDeleteDoes() {
        String id = store("alice is on holiday this week", embedder.embed("holiday", EmbedPurpose.DOCUMENT),
                NOW.minus(Duration.ofDays(1)));
        var entity = entities.upsert(WORKSPACE, "holiday", null, embedder.embed("holiday", EmbedPurpose.QUERY));
        entities.link(WORKSPACE, entity.id(), id, pair);
        assertThat(entities.entityIdsFor(WORKSPACE, id)).hasSize(1);

        reconciler.expire();

        assertThat(entities.entityIdsFor(WORKSPACE, id)).isEmpty();
        assertThat(entities.findByNorm(WORKSPACE, "holiday")).isEmpty();
    }

    /**
     * Regression: this was logged as REINFORCE, which quietly inflated the counter the reinf ranking
     * signal is built on with infrastructure failures.
     */
    @Test
    void anEmbeddingFailureHasItsOwnEventType() {
        String id = store("alice works at a bank", null, null);
        conclusions.markSyncFailed(WORKSPACE, id, "provider unavailable");

        assertThat(events.history(WORKSPACE, id, 10)).anySatisfy(e -> {
            assertThat(e.event()).isEqualTo(EventType.SYNC_FAILED);
            assertThat(e.detail()).containsEntry("sync_error", "provider unavailable");
        });
        assertThat(events.history(WORKSPACE, id, 10))
                .noneSatisfy(e -> assertThat(e.event()).isEqualTo(EventType.REINFORCE));
    }

    /**
     * The {@code sync_error} detail is a response body under another name.
     *
     * <p>{@code GET /v1/workspaces/{ws}/conclusions/{id}/events} returns each event's whole detail map
     * in a 200, so whatever the backfill records here is on the wire. The previous sweep left this
     * call on {@code getMessage} having checked that no fixture miss reaches it, which is true and
     * beside the point: the other call inside that try is {@code updateEmbedding}, a JDBC UPDATE, and
     * a {@code DataAccessException} from it is worded by the driver.
     *
     * <p>The state below is the documented half of a supported migration. {@code
     * V9__vector_width_from_configuration} tells an operator to clear the column and let this sweep
     * re-embed; do that with the width and the embedder disagreeing — the width changed, the process
     * doing the backfill still configured for the old one — and every UPDATE fails. Measured before
     * the fix, the stored detail was {@code PreparedStatementCallback; SQL [UPDATE conclusions SET
     * embedding = ?::vector, …]; ERROR: expected 512 dimensions, not 1536}.
     */
    @Test
    void aDatabaseFailureIsNotQuotedIntoTheEventTheAuditEndpointReturns() {
        String id = store("alice banks at Erste", null, null);
        jdbc.sql("ALTER TABLE conclusions ALTER COLUMN embedding TYPE vector(512)").update();
        try {
            assertThat(reconciler.syncEmbeddings()).isZero();

            assertThat(events.history(WORKSPACE, id, 10)).anySatisfy(e -> {
                assertThat(e.event()).isEqualTo(EventType.SYNC_FAILED);
                assertThat(e.detail()).containsEntry("sync_error", "an internal failure; see the server log");
            });
        } finally {
            // The container is shared by every test in this JVM, so the schema has to go back.
            jdbc.sql("UPDATE conclusions SET embedding = NULL").update();
            jdbc.sql("ALTER TABLE conclusions ALTER COLUMN embedding TYPE vector(1536)").update();
        }
    }

    @Test
    void rowsWithNoExpiryOrAFutureOneSurvive() {
        store("alice works at a bank", embedder.embed("bank", EmbedPurpose.DOCUMENT), null);
        store("alice is travelling", embedder.embed("travel", EmbedPurpose.DOCUMENT), NOW.plus(Duration.ofDays(30)));

        assertThat(reconciler.expire()).isZero();
    }

    /** An entity with no vector is invisible to matching; the sweep is what makes that temporary. */
    @Test
    void backfillsMissingEntityVectors() {
        String conclusionId = store("alice visited busan", embedder.embed("busan", EmbedPurpose.DOCUMENT), null);
        var busan = entities.upsert(WORKSPACE, "부산", null, null);
        // Edge first: matching considers only entities the pair has a link to.
        entities.link(WORKSPACE, busan.id(), conclusionId, pair);
        assertThat(entities.match(pair, embedder.embed("부산", EmbedPurpose.QUERY), 5)).isEmpty();

        assertThat(reconciler.syncEntityEmbeddings()).isEqualTo(1);
        assertThat(entities.match(pair, embedder.embed("부산", EmbedPurpose.QUERY), 5)).hasSize(1);
    }

    /**
     * The retention cutoff is computed in the JVM and compared against a column the database stamps,
     * so this one needs a live clock.
     *
     * <p>With the fixture's fixed clock it asserted on the calendar rather than on the reconciler:
     * {@code processed_at} is {@code now()}, the cutoff was a constant instant in August, and the
     * sweep therefore trimmed nothing from the day that instant passed. It was green when it was
     * written and could never be green again.
     */
    @Test
    void releasesDeadClaimsAndTrimsProcessedQueueRows() {
        WorkUnitKey key = WorkUnitKey.representation(WORKSPACE, "s1", pair);
        long id = queue.enqueue(key, Map.of("message_id", 1), 10);
        queue.markProcessed(List.of(id));
        queue.claim(key.encode(), "dead", Duration.ofSeconds(-1));

        var live = new ReconcilerService(conclusions, queue, workspaces, new EntityPipeline(entities, embedder), dreams,
                embedder, Clock.systemUTC());

        var report = live.run(Duration.ofSeconds(-1));

        assertThat(report.claimsReleased()).isEqualTo(1);
        assertThat(report.queueRowsDeleted()).isEqualTo(1);
        assertThat(queue.claim(key.encode(), "live", Duration.ofMinutes(1))).isTrue();
    }

    /**
     * A failing embedder must leave a trace. Marking the rows failed keeps the condition visible in
     * the audit log instead of the row being quietly unsearchable forever.
     */
    @Test
    void anEmbedderOutageMarksRowsFailedRatherThanLoopingForever() {
        String id = store("alice works at a bank", null, null);

        Embedder broken = new Embedder() {
            @Override
            public float[] embed(String text, EmbedPurpose purpose) {
                throw new IllegalStateException("provider unavailable");
            }

            @Override
            public List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose) {
                throw new IllegalStateException("provider unavailable");
            }

            @Override
            public int dimensions() {
                return 1536;
            }
        };

        var failing = new ReconcilerService(conclusions, queue, workspaces, new EntityPipeline(entities, broken),
                dreams, broken, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(failing.syncEmbeddings()).isZero();

        assertThat(events.history(WORKSPACE, id, 10)).anySatisfy(e -> assertThat(e.detail()).containsKey("sync_error"));
    }

    /**
     * Regression: {@code scheduleIfDue} wrote a pending dream and enqueued nothing, so the row sat
     * forever — and because the partial unique index counts a pending dream as in flight, the pair
     * could never dream again and the manual endpoint answered 409 indefinitely. The sweep is what
     * clears rows already in that state, and what covers a process dying between the two statements.
     */
    @Test
    void reQueuesADreamThatWasScheduledButNeverEnqueued() {
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CONSOLIDATE, 50).orElseThrow();
        WorkUnitKey key = WorkUnitKey.dream(pair);

        var live = new ReconcilerService(conclusions, queue, workspaces, new EntityPipeline(entities, embedder), dreams,
                embedder, Clock.systemUTC());

        // Inside the grace period nothing happens: a dream queued normally is already being worked on.
        assertThat(live.sweepOrphanedDreams().requeued()).isZero();
        assertThat(queue.pending(key.encode(), 10)).isEmpty();

        jdbc.sql("UPDATE dreams SET created_at = now() - interval '1 hour' WHERE id = ?").param(dream.id()).update();

        assertThat(live.sweepOrphanedDreams().requeued()).isEqualTo(1);
        assertThat(queue.pending(key.encode(), 10)).singleElement()
                .satisfies(item -> assertThat(item.payload()).containsEntry("dream_id", dream.id()));

        // And it does not queue the same dream twice on the next pass.
        assertThat(live.sweepOrphanedDreams().requeued()).isZero();
        assertThat(queue.pending(key.encode(), 10)).hasSize(1);
    }

    /**
     * The sweep gives up rather than paying forever.
     *
     * <p>Re-enqueueing assumes the unit was lost on its way to the queue. For a dream whose work will
     * never close the row — a consumer that leaves it pending, a batch quarantined after max-attempts
     * — the assumption never stops being true, so every pass found no pending item, enqueued another
     * unit, and bought another model call. The count is what ends it, and failing the row is also what
     * releases the partial unique index so the pair is not locked out of dreaming for good.
     */
    @Test
    void abandonsADreamItHasRequeuedTooManyTimes() {
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CONSOLIDATE, 50).orElseThrow();
        WorkUnitKey key = WorkUnitKey.dream(pair);
        var live = new ReconcilerService(conclusions, queue, workspaces, new EntityPipeline(entities, embedder), dreams,
                embedder, Clock.systemUTC());

        for (int pass = 0; pass < 3; pass++) {
            age(dream.id());
            drainQueue(key);
            assertThat(live.sweepOrphanedDreams().requeued()).isEqualTo(1);
        }

        age(dream.id());
        drainQueue(key);
        var sweep = live.sweepOrphanedDreams();
        assertThat(sweep.requeued()).isZero();
        assertThat(sweep.abandoned()).isEqualTo(1);
        assertThat(dreams.find(dream.id()).orElseThrow().status()).isEqualTo("failed");

        // The pair can dream again, which the pending row was preventing.
        assertThat(dreams.schedule(pair, DreamRepository.DreamType.CONSOLIDATE, 50)).isPresent();
    }

    private void age(String dreamId) {
        jdbc.sql("UPDATE dreams SET created_at = now() - interval '1 hour' WHERE id = ?").param(dreamId).update();
    }

    /** Stand in for a consumer that ran and never closed the dream row. */
    private void drainQueue(WorkUnitKey key) {
        queue.markProcessed(queue.pending(key.encode(), 10).stream().map(QueueRepository.QueueItem::id).toList());
    }
}
