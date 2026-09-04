package at.aimon.memory.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Poll interval that doubles while the queue is empty and collapses the moment it is not.
 *
 * <p>A fixed one-second poll from a dozen idle workers is a steady stream of aggregate queries
 * against a database with nothing to do. Doubling to thirty seconds costs at most thirty seconds of
 * latency on the first message after a quiet period, and the idle-flush gate means that message was
 * going to wait a few seconds anyway.
 *
 * <p>The initial delay is jittered so that a fleet restarted together does not poll in lockstep
 * forever after.
 */
public final class PollingBackoff {

    private final long minMillis;
    private final long maxMillis;
    private long currentMillis;

    public PollingBackoff(Duration min, Duration max) {
        this.minMillis = Math.max(1, min.toMillis());
        this.maxMillis = Math.max(minMillis, max.toMillis());
        this.currentMillis = minMillis;
    }

    /** Randomised first delay, so restarts do not synchronise the fleet. */
    public long startupJitterMillis() {
        return ThreadLocalRandom.current().nextLong(0, maxMillis);
    }

    public long nextDelayMillis(boolean didWork) {
        if (didWork) {
            currentMillis = minMillis;
        } else {
            currentMillis = Math.min(maxMillis, currentMillis * 2);
        }
        return currentMillis;
    }

    public long currentDelayMillis() {
        return currentMillis;
    }
}
