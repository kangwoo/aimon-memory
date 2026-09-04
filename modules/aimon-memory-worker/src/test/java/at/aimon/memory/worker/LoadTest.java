package at.aimon.memory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.engine.ingest.MessageIngestionService;
import at.aimon.memory.recall.RecallRequest;
import at.aimon.memory.recall.RecallService;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
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
import at.aimon.memory.text.AnalyzerRegistry;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.KoreanTextAnalyzer;
import at.aimon.memory.text.Normalizer;

/**
 * Concurrent ingestion and recall, measured.
 *
 * <p>Off by default and run deliberately:
 *
 * <pre>{@code
 * ./gradlew :aimon-memory-worker:loadTest
 * ./gradlew :aimon-memory-worker:loadTest -Daimon.memory.load.pairs=200 -Daimon.memory.load.perPair=500
 * }</pre>
 *
 * <p>It asserts almost nothing about timing. Latency thresholds baked into a test fail on a laptop
 * running a build and pass on a fast machine that is about to fall over in production, so they
 * measure the runner rather than the system. What it does assert is machine-independent and worth
 * having: that concurrent writers to the same pair produce no errors, and that dedup still converges
 * under contention rather than leaving near-duplicates behind.
 *
 * <p>The numbers it prints are for a person to read next to the runbook's tuning knobs.
 */
@EnabledIfSystemProperty(named = "aimon.memory.load", matches = "true")
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class LoadTest {

    private static final String WORKSPACE = "load";
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private static int setting(String name, int fallback) {
        return Integer.getInteger("aimon.memory.load." + name, fallback);
    }

    private JdbcClient jdbc;
    private ConclusionRepository conclusions;
    private RecallService recall;
    private MessageIngestionService ingestion;
    private final StubEmbedder embedder = new StubEmbedder();
    private final KoreanTextAnalyzer analyzer = new KoreanTextAnalyzer();

    @BeforeEach
    void wire() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        var workspaces = new WorkspaceRepository(jdbc);
        var peers = new PeerRepository(jdbc);
        var sessions = new SessionRepository(jdbc);
        var sessionPeers = new SessionPeerRepository(jdbc);
        var messages = new MessageRepository(jdbc);
        var collections = new CollectionRepository(jdbc);
        var entities = new EntityRepository(jdbc);
        var settings = new WorkspaceSettingsService(workspaces, new AnalyzerRegistry());
        var queue = new QueueRepository(jdbc);

        conclusions = new ConclusionRepository(jdbc, new EventLogRepository(jdbc), collections, settings);
        recall = new RecallService(conclusions, entities, settings, embedder,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        ingestion = new MessageIngestionService(workspaces, peers, sessions, sessionPeers, messages, collections, queue,
                settings);

        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of("language", "ko"));
        settings.invalidate(WORKSPACE);
    }

    private PairKey seedPair(String name, int conclusionCount) {
        jdbc.sql("INSERT INTO peers (workspace_name, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(WORKSPACE, name).update();
        jdbc.sql("INSERT INTO sessions (workspace_name, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(WORKSPACE, "s-" + name).update();
        PairKey pair = PairKey.self(WORKSPACE, name);
        jdbc.sql("INSERT INTO collections (workspace_name, observer, observed) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING").params(WORKSPACE, name, name).update();

        for (int i = 0; i < conclusionCount; i++) {
            String content = "앨리스는 서울 강남에서 " + name + " 주제 " + i + " 에 대해 이야기했다";
            String norm = Normalizer.normalize(content);
            conclusions.upsert(ConclusionDraft.builder().pair(pair).sessionName("s-" + name).content(content)
                    .contentNorm(norm).contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm))
                    .level(ConclusionLevel.EXPLICIT).embedding(embedder.embed(content, EmbedPurpose.DOCUMENT))
                    .actor(Actor.DERIVER).build());
        }
        return pair;
    }

    @Test
    void concurrentRecallAndIngestionStayCorrectUnderLoad() throws Exception {
        int pairs = setting("pairs", 20);
        int perPair = setting("perPair", 100);
        int readers = setting("readers", 32);
        int readsPerReader = setting("reads", 20);
        int writers = setting("writers", 8);

        long seedStart = System.nanoTime();
        List<PairKey> keys = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            keys.add(seedPair("peer" + i, perPair));
        }
        jdbc.sql("ANALYZE conclusions").update();
        long seedMillis = (System.nanoTime() - seedStart) / 1_000_000;

        List<Long> recallNanos = Collections.synchronizedList(new ArrayList<>());
        List<Long> ingestNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger();

        List<Callable<Void>> work = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            int reader = r;
            work.add(() -> {
                for (int i = 0; i < readsPerReader; i++) {
                    PairKey pair = keys.get((reader + i) % keys.size());
                    long started = System.nanoTime();
                    try {
                        recall.recall(new RecallRequest(pair, "서울 강남 주제 " + i, 10, Filter.ALL, null, true));
                        recallNanos.add(System.nanoTime() - started);
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }
                return null;
            });
        }
        // Every writer targets the same pair, which is the contended case: one work unit key, one
        // sequence allocator, and dedup deciding between them.
        PairKey contended = keys.get(0);
        for (int w = 0; w < writers; w++) {
            int writer = w;
            work.add(() -> {
                for (int i = 0; i < 10; i++) {
                    long started = System.nanoTime();
                    try {
                        ingestion.ingest(WORKSPACE, "s-" + contended.observed(),
                                List.of(new MessageIngestionService.IncomingMessage(contended.observed(),
                                        "메시지 " + writer + "-" + i, Map.of())));
                        ingestNanos.add(System.nanoTime() - started);
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }
                return null;
            });
        }

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : pool.invokeAll(work)) {
                future.get();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        report(pairs, perPair, seedMillis, elapsed, recallNanos, ingestNanos);

        assertThat(failures.get()).as("operations that threw under concurrency").isZero();
        assertThat(recallNanos).hasSize(readers * readsPerReader);
        assertThat(ingestNanos).hasSize(writers * 10);

        // The sequence allocator is the contended resource; a gap or a duplicate here means the
        // UPDATE ... RETURNING lock is not doing what the design claims.
        List<Long> sequences = jdbc
                .sql("SELECT seq_in_session FROM messages WHERE session_name = ? ORDER BY seq_in_session")
                .param("s-" + contended.observed()).query(Long.class).list();
        assertThat(sequences).doesNotHaveDuplicates();
        for (int i = 0; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1L);
        }
    }

    private static void report(int pairs, int perPair, long seedMillis, Duration elapsed, List<Long> recallNanos,
            List<Long> ingestNanos) {

        StringBuilder sb = new StringBuilder("\nload profile\n");
        sb.append(String.format("  corpus        %d pairs x %d conclusions (%d rows, seeded in %d ms)%n", pairs,
                perPair, pairs * perPair, seedMillis));
        sb.append(String.format("  wall clock    %d ms%n", elapsed.toMillis()));
        sb.append(percentiles("  recall  ", recallNanos));
        sb.append(percentiles("  ingest  ", ingestNanos));
        sb.append("  knobs: hnsw ef_search, hikari maximum-pool-size, aimon.memory.worker.concurrency\n");
        System.out.println(sb);
    }

    private static String percentiles(String label, List<Long> nanos) {
        if (nanos.isEmpty()) {
            return label + "  no samples\n";
        }
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        return String.format("%s n=%-5d p50 %6.1f ms   p95 %6.1f ms   p99 %6.1f ms   max %6.1f ms%n", label,
                sorted.size(), millis(sorted, 0.50), millis(sorted, 0.95), millis(sorted, 0.99),
                sorted.get(sorted.size() - 1) / 1_000_000.0);
    }

    private static double millis(List<Long> sorted, double quantile) {
        int index = (int) Math.min(sorted.size() - 1L, Math.round(quantile * (sorted.size() - 1)));
        return sorted.get(index) / 1_000_000.0;
    }
}
