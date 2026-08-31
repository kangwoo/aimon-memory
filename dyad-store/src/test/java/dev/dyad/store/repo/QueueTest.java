package dev.dyad.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.config.BatchSettings;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.store.StoreTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QueueTest extends StoreTestBase {

    private static final BatchSettings NEVER_READY =
            new BatchSettings(1_000_000, Duration.ofDays(365), Duration.ofDays(365));

    private WorkUnitKey key() {
        return WorkUnitKey.representation(WORKSPACE, "s1", new PairKey(WORKSPACE, "alice", "alice"));
    }

    /**
     * Exactly one claimant, always.
     *
     * <p>Claiming is an insert against a primary key rather than a lock, so this is the database's
     * uniqueness guarantee rather than the application's — which is what lets a claim outlive its
     * transaction and survive a thirty-second model call without holding a connection.
     */
    @Test
    void onlyOneWorkerClaimsAKey() throws Exception {
        String encoded = key().encode();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> attempts =
                    IntStream.range(0, 20)
                            .<Callable<Boolean>>mapToObj(
                                    i -> () -> queue.claim(encoded, "worker-" + i, Duration.ofMinutes(1)))
                            .toList();
            long winners =
                    pool.invokeAll(attempts).stream()
                            .filter(future -> {
                                try {
                                    return future.get();
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            })
                            .count();
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void releaseOnlyWorksForTheHolder() {
        String encoded = key().encode();
        assertThat(queue.claim(encoded, "worker-a", Duration.ofMinutes(1))).isTrue();

        queue.release(encoded, "worker-b");
        assertThat(queue.claim(encoded, "worker-c", Duration.ofMinutes(1))).isFalse();

        queue.release(encoded, "worker-a");
        assertThat(queue.claim(encoded, "worker-c", Duration.ofMinutes(1))).isTrue();
    }

    /** A worker that dies mid-batch must not block its key forever. */
    @Test
    void expiredClaimsAreReleased() {
        String encoded = key().encode();
        assertThat(queue.claim(encoded, "dead-worker", Duration.ofSeconds(-1))).isTrue();

        assertThat(queue.releaseExpiredClaims()).isEqualTo(1);
        assertThat(queue.claim(encoded, "live-worker", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void tokenThresholdMakesAUnitReady() {
        queue.enqueue(key(), Map.of("message_id", 1), 300);
        assertThat(queue.ready(BatchSettings.DEFAULT, 10)).isEmpty();

        queue.enqueue(key(), Map.of("message_id", 2), 300);
        assertThat(queue.ready(BatchSettings.DEFAULT, 10))
                .singleElement()
                .satisfies(unit -> {
                    assertThat(unit.tokens()).isEqualTo(600);
                    assertThat(unit.items()).isEqualTo(2);
                });
    }

    /**
     * The gate that makes conclusions visible inside a conversation rather than up to half an hour
     * later. People speak in bursts and then pause; the pause is the flush point.
     */
    @Test
    void idleFlushMakesASmallBatchReady() {
        queue.enqueue(key(), Map.of("message_id", 1), 5);
        assertThat(queue.ready(NEVER_READY, 10)).isEmpty();

        BatchSettings idleNow =
                new BatchSettings(1_000_000, Duration.ofDays(365), Duration.ofSeconds(-1));
        assertThat(queue.ready(idleNow, 10)).hasSize(1);
    }

    @Test
    void ageMakesALargeQuietBatchReady() {
        queue.enqueue(key(), Map.of("message_id", 1), 5);
        BatchSettings agedOut =
                new BatchSettings(1_000_000, Duration.ofSeconds(-1), Duration.ofDays(365));
        assertThat(queue.ready(agedOut, 10)).hasSize(1);
    }

    /** A claimed unit must not be offered to a second worker on the next poll. */
    @Test
    void claimedUnitsAreHiddenFromReady() {
        queue.enqueue(key(), Map.of("message_id", 1), 1000);
        assertThat(queue.ready(BatchSettings.DEFAULT, 10)).hasSize(1);

        queue.claim(key().encode(), "worker-a", Duration.ofMinutes(5));
        assertThat(queue.ready(BatchSettings.DEFAULT, 10)).isEmpty();
    }

    @Test
    void processedItemsLeaveTheQueueAndCanBeTrimmed() {
        long id = queue.enqueue(key(), Map.of("message_id", 1), 1000);
        assertThat(queue.pendingCount(WORKSPACE)).isEqualTo(1);

        queue.markProcessed(List.of(id));
        assertThat(queue.pendingCount(WORKSPACE)).isZero();
        assertThat(queue.deleteProcessedBefore(java.time.Instant.now().plusSeconds(60))).isEqualTo(1);
    }

    /** The unscoped scan and the per-workspace one must agree, or gating silently drops work. */
    @Test
    void perWorkspaceGatingSeesTheSameUnitsAsAGlobalScan() {
        queue.enqueue(key(), Map.of("message_id", 1), 1000);
        queue.enqueue(
                new WorkUnitKey(
                        dev.dyad.core.key.TaskType.REPRESENTATION, WORKSPACE, "s2", "bob", "bob"),
                Map.of("message_id", 2), 1000);

        assertThat(queue.workspacesWithPendingWork(10)).containsExactly(WORKSPACE);
        assertThat(queue.ready(WORKSPACE, BatchSettings.DEFAULT, 10)).hasSize(2);
        assertThat(queue.ready(BatchSettings.DEFAULT, 10)).hasSize(2);
        assertThat(queue.ready("other-workspace", BatchSettings.DEFAULT, 10)).isEmpty();
    }

    @Test
    void failuresCountAttemptsWithoutConsumingTheItem() {
        long id = queue.enqueue(key(), Map.of("message_id", 1), 1000);
        queue.recordFailure(List.of(id), "provider timeout");

        var pending = queue.pending(key().encode(), 10);
        assertThat(pending).singleElement().satisfies(item -> assertThat(item.attempts()).isEqualTo(1));

        // Quarantine is the escape hatch: a poison batch on a serialised key blocks everything behind it.
        queue.quarantine(List.of(id));
        assertThat(queue.pending(key().encode(), 10)).isEmpty();
    }
}
