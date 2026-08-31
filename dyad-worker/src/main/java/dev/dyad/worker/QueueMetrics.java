package dev.dyad.worker;

import dev.dyad.store.repo.QueueRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publishes queue depth and queue age.
 *
 * <p>Sampled on a timer into a cached value rather than queried when Prometheus scrapes. A gauge that
 * runs an aggregate query on every scrape gives every monitoring system that points at the service
 * the ability to load the database, and the numbers are not more useful for being ten seconds fresher.
 *
 * <p>Age matters more than depth. A deep queue that is draining is a busy system; a shallow queue
 * whose oldest item keeps getting older is a stalled one, and only the second is an incident.
 */
@Component
public class QueueMetrics implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);
    private static final long SAMPLE_INTERVAL_MILLIS = 15_000;

    private final QueueRepository queue;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private volatile boolean running;
    private Thread sampler;

    public QueueMetrics(QueueRepository queue, MeterRegistry meters) {
        this.queue = queue;
        Gauge.builder("dyad.queue.pending", pending, AtomicLong::doubleValue)
                .description("Unprocessed queue items across all workspaces")
                .register(meters);
        Gauge.builder("dyad.queue.oldest.seconds", oldestSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest unprocessed queue item")
                .baseUnit("seconds")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sampler = Thread.ofPlatform().name("dyad-queue-metrics").daemon().start(this::sampleForever);
    }

    private void sampleForever() {
        while (running) {
            sample();
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Visible for tests, and for a caller that wants a fresh reading immediately after a change. */
    public void sample() {
        try {
            pending.set(queue.pendingCount());
            oldestSeconds.set((long) queue.oldestPendingSeconds());
        } catch (RuntimeException e) {
            // Losing a sample is not worth taking the worker down for; the next one is 15 seconds away.
            log.warn("queue metric sample failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        if (sampler != null) {
            sampler.interrupt();
        }
    }
}
