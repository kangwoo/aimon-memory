package at.aimon.memory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.engine.dream.PeerCardService;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.worker.consumer.DreamConsumer;

/**
 * A card refresh has to close the dream row it was scheduled from.
 *
 * <p>It did not, and nothing noticed, because leaving a row {@code pending} looks exactly like work
 * that has not started. {@code ReconcilerService.sweepOrphanedDreams} exists to rescue a dream whose
 * work unit never reached the queue, and it could not tell the two apart: every pass found no pending
 * queue item, concluded the unit was lost, and enqueued another — one model call per reconciler
 * interval, for the life of the deployment. Meanwhile the partial unique index counts a pending row
 * as in flight, so the pair could never dream again and {@code POST /dreams} answered 409 for good.
 */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class CardRefreshConsumerTest {

    private static final String WORKSPACE = "ws";

    private JdbcClient jdbc;
    private DreamRepository dreams;
    private QueueRepository queue;
    private PairKey pair;
    private PeerCardService cards;
    private DreamConsumer.CardRefreshConsumer consumer;

    @BeforeEach
    void wire() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        new WorkspaceRepository(jdbc).getOrCreate(WORKSPACE, Map.of(), Map.of());
        new PeerRepository(jdbc).getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        pair = PairKey.self(WORKSPACE, "alice");
        new CollectionRepository(jdbc).getOrCreate(pair);

        dreams = new DreamRepository(jdbc);
        queue = new QueueRepository(jdbc);
        cards = mock(PeerCardService.class);
        consumer = new DreamConsumer.CardRefreshConsumer(cards, dreams);
    }

    private List<QueueRepository.QueueItem> enqueued(String dreamId) {
        WorkUnitKey key = WorkUnitKey.cardRefresh(pair);
        queue.enqueue(key, Map.of("dream_id", dreamId), 0);
        return queue.pending(key.encode(), 10);
    }

    @Test
    void aSuccessfulRefreshCompletesTheDream() {
        when(cards.refresh(any())).thenReturn(List.of("- works at a bank", "- commutes by bicycle"));
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CARD_REFRESH, 12).orElseThrow();

        consumer.consume(WorkUnitKey.cardRefresh(pair), enqueued(dream.id()));

        var closed = dreams.find(dream.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("completed");
        assertThat(closed.produced()).isEqualTo(2);

        // Which is what lets the pair dream again: the partial unique index counts pending as in flight.
        assertThat(dreams.schedule(pair, DreamRepository.DreamType.CONSOLIDATE, 12)).isPresent();
    }

    @Test
    void aFailedRefreshFailsTheDreamRatherThanLeavingItPending() {
        when(cards.refresh(any()))
                .thenThrow(new MemoryException("card_generation_failed", "no usable lines"));
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CARD_REFRESH, 12).orElseThrow();
        var items = enqueued(dream.id());

        assertThatThrownBy(() -> consumer.consume(WorkUnitKey.cardRefresh(pair), items))
                .isInstanceOf(MemoryException.class);

        var closed = dreams.find(dream.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("failed");
        assertThat(closed.error()).contains("no usable lines");
    }

    /**
     * The failure message stored here is a response body under another name.
     *
     * <p>{@code Dtos.DreamResponse} returns {@code dreams.error}, so {@code GET /dreams} is a 200 that
     * carries whatever this stored — which is why the consumer reads {@code publicMessage} rather than
     * {@code getMessage}. The exception that needs it is {@code FixtureMissException}: a card refresh
     * is a model call, so a deployment left on the default replay mode fails one with the assembled
     * prompt and an absolute server path in the message. This module cannot see that class, so the
     * contract is exercised directly — any {@code MemoryException} that distinguishes the two.
     */
    @Test
    void aMessageWrittenForSomebodyElseIsNotStoredInAColumnAResponseReturns() {
        when(cards.refresh(any())).thenThrow(new MemoryException("fixture_miss", "the whole assembled prompt") {
            @Override
            public String publicMessage() {
                return "see the server log";
            }
        });
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CARD_REFRESH, 12).orElseThrow();
        var items = enqueued(dream.id());

        assertThatThrownBy(() -> consumer.consume(WorkUnitKey.cardRefresh(pair), items))
                .isInstanceOf(MemoryException.class).hasMessage("the whole assembled prompt");

        var closed = dreams.find(dream.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("failed");
        assertThat(closed.error()).isEqualTo("see the server log");
    }

    /** A unit enqueued without a dream id — nothing scheduled it — still refreshes and closes nothing. */
    @Test
    void aUnitWithNoDreamIdStillRefreshes() {
        when(cards.refresh(any())).thenReturn(List.of("- a line"));
        WorkUnitKey key = WorkUnitKey.cardRefresh(pair);
        queue.enqueue(key, Map.of(), 0);

        consumer.consume(key, queue.pending(key.encode(), 10));

        assertThat(dreams.forPair(pair, 10)).isEmpty();
    }
}
