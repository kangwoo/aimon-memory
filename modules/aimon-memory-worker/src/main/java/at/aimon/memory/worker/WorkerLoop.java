package at.aimon.memory.worker;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.worker.consumer.WorkUnitConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The poll-claim-consume loop.
 *
 * <p>Claims are taken by insert, so a claim outlives its transaction and no database session is held
 * open across a model call. It is released in a {@code finally}, and if the process dies before that
 * the TTL releases it instead — which is why a unit that is still running has to keep saying so. A
 * dream over two hundred conclusions makes three sequential model calls and outlives the default
 * five-minute TTL comfortably; without the heartbeat the reconciler reaped its claim mid-flight and a
 * second worker picked up the same still-unprocessed items, paying twice and racing in dedup.
 *
 * <p>Work units run on virtual threads. Each one spends most of its life waiting on a provider, which
 * is exactly the workload virtual threads exist for — the concurrency limit here is about how many
 * model calls to have outstanding, not how many OS threads to fund.
 */
@Component
public class WorkerLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    private final QueueRepository queue;
    private final WorkerProperties properties;
    private final ReconcilerService reconciler;
    private final Map<TaskType, WorkUnitConsumer> consumers = new EnumMap<>(TaskType.class);
    private final at.aimon.memory.store.WorkspaceSettingsService settings;
    private final MeterRegistry meters;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Semaphore slots;
    private ExecutorService workers;
    private ScheduledExecutorService heartbeats;
    private Thread pollThread;
    private Thread reconcilerThread;

    public WorkerLoop(QueueRepository queue, WorkerProperties properties, ReconcilerService reconciler,
            List<WorkUnitConsumer> consumerList, at.aimon.memory.store.WorkspaceSettingsService settings,
            MeterRegistry meters) {
        this.queue = queue;
        this.properties = properties;
        this.reconciler = reconciler;
        this.settings = settings;
        this.meters = meters;
        this.slots = new Semaphore(properties.concurrency());
        consumerList.forEach(consumer -> consumers.put(consumer.taskType(), consumer));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        // One scheduler thread per in-flight unit, not one for all of them.
        //
        // Every heartbeat is a JDBC UPDATE, so it needs a pool connection — and the work units it is
        // keeping alive are holding connections of their own. On a single thread, one extension
        // blocked on connection acquisition holds up every other unit's heartbeat behind it; two
        // stalled cycles at a third of the TTL are enough for the reconciler to reap claims from
        // units that are still running, which is the duplicate-run race the heartbeat was added to
        // prevent, reintroduced through a shared point of failure.
        heartbeats = Executors.newScheduledThreadPool(Math.max(1, properties.concurrency()),
                r -> Thread.ofPlatform().name("aimon-memory-claim-heartbeat", 0).unstarted(r));
        pollThread = Thread.ofPlatform().name("aimon-memory-poll").start(this::pollForever);
        reconcilerThread = Thread.ofPlatform().name("aimon-memory-reconciler").start(this::reconcileForever);
        log.info("worker {} started with concurrency {}", properties.workerId(), properties.concurrency());
    }

    private void pollForever() {
        PollingBackoff backoff = new PollingBackoff(properties.minPollInterval(), properties.maxPollInterval());
        sleep(backoff.startupJitterMillis());
        while (running.get()) {
            boolean didWork = false;
            try {
                didWork = pollOnce();
            } catch (RuntimeException e) {
                log.error("poll failed", e);
            }
            sleep(backoff.nextDelayMillis(didWork));
        }
    }

    /**
     * One poll: find the workspaces with work, gate each by its own batch settings, claim what is
     * ready.
     *
     * <p>Per workspace rather than globally, because the batch gate is workspace configuration. A
     * tenant that sets a three-second idle flush has to get one.
     *
     * @return true when at least one work unit was picked up
     */
    public boolean pollOnce() {
        boolean claimedAny = false;
        for (String workspace : queue.workspacesWithPendingWork(properties.unitsPerPoll())) {
            var batchSettings = settings.forWorkspace(workspace).batch();
            for (var unit : queue.ready(workspace, batchSettings, properties.unitsPerPoll())) {
                if (!slots.tryAcquire()) {
                    return claimedAny;
                }
                // From the acquire to the hand-off, this method owns the permit, so it has to give it
                // back itself if anything in between throws — runClaimed's finally only runs once the
                // task has actually started.
                //
                // Both calls below can throw. claim is a JDBC insert, so a moment of pool exhaustion
                // is enough; submit throws RejectedExecutionException once close() has shut the
                // executor down. Neither was guarded, and the exception went to pollForever's catch,
                // which logs and polls again — so each one burned a permit permanently. After
                // `concurrency` of them tryAcquire never succeeds again: the loop keeps polling, the
                // queue keeps filling, the process stays healthy and says nothing, and only a
                // restart clears it.
                //
                // A claim whose submit was rejected is left to its TTL rather than released here.
                // That path is reachable only from close(), where the process is going away anyway
                // and the TTL is the mechanism that already covers a worker that stopped mid-unit.
                boolean handedOff = false;
                try {
                    if (!queue.claim(unit.workUnitKey(), properties.workerId(), properties.claimTtl())) {
                        continue;
                    }
                    claimedAny = true;
                    workers.submit(() -> runClaimed(unit.workUnitKey()));
                    handedOff = true;
                } finally {
                    if (!handedOff) {
                        slots.release();
                    }
                }
            }
        }
        return claimedAny;
    }

    private void runClaimed(String encodedKey) {
        Timer.Sample sample = Timer.start(meters);
        List<QueueRepository.QueueItem> items = List.of();
        // Inside the try, and null until it exists. scheduleAtFixedRate throws
        // RejectedExecutionException once the executor is shut down — reachable from close(), where
        // awaitTermination can time out and shutdownNow() then runs while units are still starting.
        // Thrown from above the try, that skipped the finally: the claim lingered until its TTL and
        // the concurrency permit was never given back, so the worker ran one slot narrower for the
        // rest of the process's life, and again for every unit that lost the same race.
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = startHeartbeat(encodedKey);
            WorkUnitKey key = WorkUnitKey.parse(encodedKey);
            items = queue.pending(encodedKey, properties.itemsPerUnit());
            if (items.isEmpty()) {
                return;
            }
            WorkUnitConsumer consumer = consumers.get(key.taskType());
            if (consumer == null) {
                log.warn("no consumer for {}; quarantining {} items", key.taskType(), items.size());
                queue.quarantine(ids(items));
                return;
            }
            consumer.consume(key, items);
            queue.markProcessed(ids(items));
            meters.counter("aimon.memory.worker.items", "task", key.taskType().wire()).increment(items.size());
        } catch (RuntimeException e) {
            log.error("work unit {} failed: {}", encodedKey, e.getMessage(), e);
            queue.recordFailure(ids(items), e.getMessage());
            quarantineIfExhausted(items);
        } finally {
            // The permit comes back first, before anything else in this block runs.
            //
            // It used to come back after `queue.release`, which is a JDBC DELETE — so the same moment
            // of pool exhaustion that `pollOnce` now guards against lost a permit here instead, and
            // lost it more often: `claim` runs once per poll, this runs once per completed work unit.
            // Worse, it was silent twice over. The throw leaves `runClaimed` into the Runnable that
            // `workers.submit` wrapped, where it lands in a Future nobody reads, so not even the
            // "work unit failed" line above gets logged.
            //
            // Stated as "first" rather than "before the JDBC call" on purpose. Ordering it against one
            // named statement is a rule the next edit can break by inserting a line above it; ordering
            // it against the whole block is not.
            //
            // Nothing below needs the permit still held. The claim row outliving the permit by a few
            // microseconds cannot cause a double run — the poll thread that takes this permit has to
            // get past `queue.claim` for the unit, and the row is still there until the last line.
            slots.release();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            sample.stop(meters.timer("aimon.memory.worker.unit"));
            // Last, because it is the only statement here that can throw and the only one with a
            // fallback of its own: a claim this fails to delete lapses at its TTL, which is the
            // mechanism already covering a worker that died mid-unit.
            queue.release(encodedKey, properties.workerId());
        }
    }

    /**
     * Keep saying "still mine" for as long as the unit runs.
     *
     * <p>A third of the TTL, so two consecutive failed extensions still leave time for a third before
     * the claim lapses. An extension that throws is logged and dropped rather than killing the unit:
     * losing the claim costs a duplicate run, and aborting work that is mid-way through a model call
     * costs the same thing plus the call.
     */
    private ScheduledFuture<?> startHeartbeat(String encodedKey) {
        long everyMillis = Math.max(1_000L, properties.claimTtl().toMillis() / 3);
        return heartbeats.scheduleAtFixedRate(() -> {
            try {
                queue.extendClaim(encodedKey, properties.workerId(), properties.claimTtl());
            } catch (RuntimeException e) {
                log.warn("could not extend claim on {}: {}", encodedKey, e.getMessage());
            }
        }, everyMillis, everyMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Give up on a batch that keeps failing.
     *
     * <p>Because the queue serialises on the key, a batch that always throws blocks every later batch
     * for the same pair indefinitely. Dropping it after a few attempts loses those messages'
     * conclusions; not dropping it loses every conclusion after them.
     */
    private void quarantineIfExhausted(List<QueueRepository.QueueItem> items) {
        List<Long> exhausted = items.stream().filter(item -> item.attempts() + 1 >= properties.maxAttempts())
                .map(QueueRepository.QueueItem::id).toList();
        if (!exhausted.isEmpty()) {
            log.error("quarantining {} queue items after {} attempts", exhausted.size(), properties.maxAttempts());
            queue.quarantine(exhausted);
            meters.counter("aimon.memory.worker.quarantined").increment(exhausted.size());
        }
    }

    private void reconcileForever() {
        while (running.get()) {
            sleep(properties.reconcilerInterval().toMillis());
            if (!running.get()) {
                return;
            }
            try {
                reconciler.run(properties.processedRetention());
            } catch (RuntimeException e) {
                log.error("reconciler pass failed", e);
            }
        }
    }

    private static List<Long> ids(List<QueueRepository.QueueItem> items) {
        return items.stream().map(QueueRepository.QueueItem::id).toList();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (reconcilerThread != null) {
            reconcilerThread.interrupt();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                workers.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
    }
}
