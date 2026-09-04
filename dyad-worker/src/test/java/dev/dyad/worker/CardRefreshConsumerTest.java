package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dyad.core.DyadException;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.memory.dream.PeerCardService;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.DreamRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import dev.dyad.worker.consumer.DreamConsumer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

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
                .thenThrow(new DyadException("card_generation_failed", "no usable lines"));
        var dream = dreams.schedule(pair, DreamRepository.DreamType.CARD_REFRESH, 12).orElseThrow();
        var items = enqueued(dream.id());

        assertThatThrownBy(() -> consumer.consume(WorkUnitKey.cardRefresh(pair), items))
                .isInstanceOf(DyadException.class);

        var closed = dreams.find(dream.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("failed");
        assertThat(closed.error()).contains("no usable lines");
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
