package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.model.EventType;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.core.spi.Embedder;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.store.repo.EntityRepository;
import dev.dyad.store.repo.EventLogRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import dev.dyad.testkit.stub.StubAnalyzer;
import dev.dyad.testkit.stub.StubEmbedder;
import dev.dyad.text.ContentHash;
import dev.dyad.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

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
        conclusions =
                new ConclusionRepository(
                        jdbc, events, collections,
                        new dev.dyad.store.WorkspaceSettingsService(
                                workspaces, new dev.dyad.text.AnalyzerRegistry()));
        queue = new QueueRepository(jdbc);
        entities = new EntityRepository(jdbc);
        reconciler =
                new ReconcilerService(
                        conclusions, queue, workspaces, new EntityPipeline(entities, embedder), embedder,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        sessions.getOrCreate(WORKSPACE, "s1", Map.of(), Map.of());
        pair = PairKey.self(WORKSPACE, "alice");
        collections.getOrCreate(pair);
    }

    @SuppressWarnings("UnusedReturnValue")
    private String store(String content, float[] embedding, Instant expiresAt) {
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
                                .embedding(embedding)
                                .expiresAt(expiresAt)
                                .actor(Actor.DERIVER)
                                .build())
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
        assertThat(conclusions.semantic(pair, embedder.embed("bank", EmbedPurpose.QUERY), 5, Filter.ALL))
                .isNotEmpty();
    }

    /** Expiry is the system's own decision, so it is logged as EXPIRE rather than DELETE. */
    @Test
    void expiredRowsAreSoftDeletedAndLoggedAsExpiry() {
        String id =
                store("alice is on holiday this week", embedder.embed("holiday", EmbedPurpose.DOCUMENT),
                        NOW.minus(Duration.ofDays(1)));

        assertThat(reconciler.expire()).isEqualTo(1);
        assertThat(conclusions.find(WORKSPACE, id).orElseThrow().isDeleted()).isTrue();
        assertThat(events.history(WORKSPACE, id, 10))
                .anySatisfy(e -> {
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
        String id =
                store("alice is on holiday this week", embedder.embed("holiday", EmbedPurpose.DOCUMENT),
                        NOW.minus(Duration.ofDays(1)));
        var entity =
                entities.upsert(WORKSPACE, "holiday", null, embedder.embed("holiday", EmbedPurpose.QUERY));
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

        assertThat(events.history(WORKSPACE, id, 10))
                .anySatisfy(e -> {
                    assertThat(e.event()).isEqualTo(EventType.SYNC_FAILED);
                    assertThat(e.detail()).containsEntry("sync_error", "provider unavailable");
                });
        assertThat(events.history(WORKSPACE, id, 10))
                .noneSatisfy(e -> assertThat(e.event()).isEqualTo(EventType.REINFORCE));
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
        entities.upsert(WORKSPACE, "부산", null, null);
        assertThat(entities.match(WORKSPACE, embedder.embed("부산", EmbedPurpose.QUERY), 5)).isEmpty();

        assertThat(reconciler.syncEntityEmbeddings()).isEqualTo(1);
        assertThat(entities.match(WORKSPACE, embedder.embed("부산", EmbedPurpose.QUERY), 5)).hasSize(1);
    }

    @Test
    void releasesDeadClaimsAndTrimsProcessedQueueRows() {
        WorkUnitKey key = WorkUnitKey.representation(WORKSPACE, "s1", pair);
        long id = queue.enqueue(key, Map.of("message_id", 1), 10);
        queue.markProcessed(List.of(id));
        queue.claim(key.encode(), "dead", Duration.ofSeconds(-1));

        var report = reconciler.run(Duration.ofSeconds(-1));

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

        Embedder broken =
                new Embedder() {
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

        var failing =
                new ReconcilerService(
                        conclusions, queue, workspaces, new EntityPipeline(entities, broken), broken,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(failing.syncEmbeddings()).isZero();

        assertThat(events.history(WORKSPACE, id, 10))
                .anySatisfy(e -> assertThat(e.detail()).containsKey("sync_error"));
    }
}
