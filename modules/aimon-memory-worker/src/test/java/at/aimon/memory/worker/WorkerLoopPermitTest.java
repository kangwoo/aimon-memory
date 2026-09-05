package at.aimon.memory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.store.WorkspaceSettings;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.QueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The concurrency permit comes back on every path that took one.
 *
 * <p>Nothing said so. The permit-release guards in {@link WorkerLoop#pollOnce()} and in
 * {@code runClaimed}'s {@code finally} could both be deleted and the whole worker suite — {@code test}
 * and {@code integrationTest} together — stayed green, because every existing test drives the happy
 * path and the happy path was never the broken one. That is the specific shape of untested this file
 * is for: not a behaviour nobody asserted, but a guard whose absence is invisible until a database
 * has a bad minute in production.
 *
 * <p>What the guards prevent is a worker that cannot be diagnosed from outside. A lost permit is not
 * an error anyone sees: {@code pollOnce}'s exception goes to {@code pollForever}, which logs it once
 * and polls again, and {@code runClaimed}'s goes into the {@code Future} that {@code submit} returned
 * and which nothing reads — so not even a log line. After {@code concurrency} of them
 * {@code tryAcquire} never succeeds again and the process stays up, answers its health check, and
 * quietly stops doing work. Only a restart clears it.
 *
 * <p>No database. The failure being pinned is what this class does with a permit when a JDBC call
 * throws, and a mock is a better way to make one throw on demand than a real Postgres is — which is
 * also why this runs in {@code test} rather than behind the {@code docker} tag, where the guard would
 * be checked by the tier that has historically been able to skip.
 */
class WorkerLoopPermitTest {

    private static final String WORKSPACE = "ws";
    private static final String KEY = WorkUnitKey
            .representation(WORKSPACE, "s1", new PairKey(WORKSPACE, "alice", "bob")).encode();
    /** Two, so "the permit came back" is distinguishable from "there were spare permits anyway". */
    private static final int CONCURRENCY = 2;
    private static final int WAIT_SECONDS = 10;

    private QueueRepository queue;
    private WorkerLoop loop;

    /**
     * Hands out the workspace exactly once, to whoever asks first.
     *
     * <p>{@code start()} runs a poll thread of its own, and these tests also call {@code pollOnce}
     * directly. A one-shot {@code compareAndSet} means it does not matter which of the two gets there:
     * exactly one work unit is ever offered, so the permit arithmetic below is not a race.
     */
    private final AtomicBoolean armed = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        queue = mock(QueueRepository.class);
        when(queue.workspacesWithPendingWork(anyInt()))
                .thenAnswer(invocation -> armed.compareAndSet(true, false) ? List.of(WORKSPACE) : List.of());
        when(queue.ready(eq(WORKSPACE), any(), anyInt()))
                .thenReturn(List.of(new QueueRepository.ReadyUnit(KEY, 1L, 1, Instant.EPOCH, Instant.EPOCH)));

        WorkspaceSettingsService settings = mock(WorkspaceSettingsService.class);
        when(settings.forWorkspace(anyString())).thenReturn(WorkspaceSettings.DEFAULT);

        WorkerProperties properties = new WorkerProperties("permit-test", CONCURRENCY, 8, 100, Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 5, Duration.ofMinutes(5), Duration.ofDays(7));
        loop = new WorkerLoop(queue, properties, mock(ReconcilerService.class), List.of(), settings,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        // Every field close() touches is null-guarded, so this is safe on a loop that was never
        // started, and idempotent on one that was already closed by a test.
        loop.close();
    }

    /**
     * A claim that throws hands its permit back.
     *
     * <p>{@code queue.claim} is an insert against the partial unique index, so a moment of pool
     * exhaustion is all it takes. This runs without {@code start()} on purpose: the throw happens
     * before the executor is reached, which is the whole point — the permit is owned by this method,
     * not yet by a task.
     */
    @Test
    void aClaimThatThrowsGivesItsPermitBack() {
        when(queue.claim(eq(KEY), anyString(), any())).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        armed.set(true);

        assertThatThrownBy(loop::pollOnce).isInstanceOf(CannotGetJdbcConnectionException.class);

        assertThat(availablePermits()).as("the permit taken for a claim that failed").isEqualTo(CONCURRENCY);
    }

    /**
     * A hand-off rejected during shutdown hands its permit back.
     *
     * <p>The other throw between acquiring a permit and handing it over. {@code submit} raises
     * {@code RejectedExecutionException} once {@code close()} has shut the executor down, and
     * {@code close()} is reachable while the poll thread is still in the middle of a pass — so this
     * drives the real sequence rather than a stand-in for it.
     *
     * <p>The claim itself is left to its TTL here rather than released, which is what {@code pollOnce}
     * says it does: this path is only reachable from {@code close()}, where the process is going away.
     */
    @Test
    void aSubmitRejectedDuringShutdownGivesItsPermitBack() {
        when(queue.claim(eq(KEY), anyString(), any())).thenReturn(true);
        loop.start();
        loop.close();
        armed.set(true);

        assertThatThrownBy(loop::pollOnce).isInstanceOf(RejectedExecutionException.class);

        assertThat(availablePermits()).as("the permit of a unit that was never handed off").isEqualTo(CONCURRENCY);
    }

    /**
     * A release that throws at the end of a unit hands its permit back.
     *
     * <p>The one {@code pollOnce}'s guard does not cover, and the one that fires most often: a claim
     * is attempted once per poll, but {@code queue.release} runs in the {@code finally} of every
     * completed work unit, against the same pool. It used to sit ahead of {@code slots.release()} in
     * that block, so a single {@code DataAccessException} there cost a permit for the life of the
     * process — and silently, because the throw leaves {@code runClaimed} into a {@code Future} nobody
     * reads.
     *
     * <p>The latch is counted down inside the failing {@code release}, so the assertion runs at a
     * defined point rather than after a sleep: with the permit released first it is already back by
     * then, and with the old ordering it never comes back at all.
     */
    @Test
    void aReleaseThatThrowsAtTheEndOfAUnitGivesItsPermitBack() throws InterruptedException {
        CountDownLatch reachedRelease = new CountDownLatch(1);
        when(queue.claim(eq(KEY), anyString(), any())).thenReturn(true);
        // Empty, so the unit returns before any consumer is needed and the finally is all that is left.
        when(queue.pending(eq(KEY), anyInt())).thenReturn(List.of());
        doAnswer(invocation -> {
            reachedRelease.countDown();
            throw new CannotGetJdbcConnectionException("pool exhausted");
        }).when(queue).release(eq(KEY), anyString());

        loop.start();
        armed.set(true);
        loop.pollOnce();

        assertThat(reachedRelease.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the unit reached queue.release").isTrue();
        assertThat(availablePermits()).as("the permit of a unit whose release failed").isEqualTo(CONCURRENCY);
    }

    /**
     * The consequence, stated without reading the semaphore.
     *
     * <p>{@code concurrency} failures in a row is the threshold at which the old code stopped calling
     * {@code queue.claim} at all — {@code tryAcquire} returned false, {@code pollOnce} returned
     * {@code claimedAny} and threw nothing, and the worker was done for good while still logging a
     * poll every interval. Asserting on the call count says that in the form an operator would
     * recognise: the worker is still reaching for work after more failures than it has permits.
     */
    @Test
    void aWorkerThatKeepsFailingToClaimIsNotNarrowedPermanently() {
        when(queue.claim(eq(KEY), anyString(), any())).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));

        for (int attempt = 0; attempt <= CONCURRENCY; attempt++) {
            armed.set(true);
            assertThatThrownBy(loop::pollOnce).isInstanceOf(CannotGetJdbcConnectionException.class);
        }

        verify(queue, times(CONCURRENCY + 1)).claim(eq(KEY), anyString(), any());
        assertThat(availablePermits()).isEqualTo(CONCURRENCY);
    }

    /**
     * Read straight off the semaphore that bounds concurrency.
     *
     * <p>Reflection because the field is private and stays that way: widening it to package-private
     * would put a seam in production code that exists only for this file, and the count is the
     * quantity these tests are actually about. {@link #aWorkerThatKeepsFailingToClaimIsNotNarrowedPermanently()}
     * is the same claim made without it, so a rename here cannot leave the guard unasserted.
     */
    private int availablePermits() {
        try {
            Field field = WorkerLoop.class.getDeclaredField("slots");
            field.setAccessible(true);
            return ((Semaphore) field.get(loop)).availablePermits();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("WorkerLoop.slots is what bounds worker concurrency; this test is about it", e);
        }
    }
}
