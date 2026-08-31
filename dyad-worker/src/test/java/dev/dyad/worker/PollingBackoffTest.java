package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PollingBackoffTest {

    /**
     * A fixed one-second poll from an idle fleet is a steady stream of aggregate queries against a
     * database with nothing to do. Backing off costs at most one interval of latency on the first
     * message after a quiet period — which the idle-flush gate was going to spend anyway.
     */
    @Test
    void idlePollingBacksOffAndBusyPollingSnapsBack() {
        PollingBackoff backoff = new PollingBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertThat(backoff.nextDelayMillis(false)).isEqualTo(2_000);
        assertThat(backoff.nextDelayMillis(false)).isEqualTo(4_000);
        assertThat(backoff.nextDelayMillis(false)).isEqualTo(8_000);
        assertThat(backoff.nextDelayMillis(false)).isEqualTo(16_000);
        assertThat(backoff.nextDelayMillis(false)).isEqualTo(30_000);
        assertThat(backoff.nextDelayMillis(false)).as("capped").isEqualTo(30_000);

        assertThat(backoff.nextDelayMillis(true)).as("work resets the interval immediately").isEqualTo(1_000);
    }

    /** Without startup jitter, a fleet restarted together polls in lockstep forever after. */
    @Test
    void startupJitterSpreadsARestartedFleet() {
        PollingBackoff backoff = new PollingBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
        for (int i = 0; i < 100; i++) {
            assertThat(backoff.startupJitterMillis()).isBetween(0L, 30_000L);
        }
    }
}
