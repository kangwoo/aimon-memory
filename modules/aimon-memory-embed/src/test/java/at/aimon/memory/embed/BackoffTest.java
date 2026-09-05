package at.aimon.memory.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The retry delay between embedding attempts.
 *
 * <p>Only lower bounds and the interrupt path are asserted here. An upper bound on a sleep measures
 * the machine the suite runs on rather than the code, and this repository has no timing-sensitive
 * tests for that reason; the ceiling is instead pinned indirectly, by showing that an absurd attempt
 * number still produces a legal delay rather than an overflowed or negative one.
 */
class BackoffTest {

    /**
     * An interrupt is reported and the flag is put back.
     *
     * <p>The flag matters more than the exception. This runs inside a worker thread that may be
     * mid-shutdown, and a swallowed interrupt leaves that thread believing it was never asked to stop
     * — the loop above it keeps polling and the process will not come down.
     *
     * <p>Interrupting before the call rather than from another thread: {@code Thread.sleep} throws
     * immediately when the flag is already set, so this is deterministic and costs no wall time.
     */
    @Test
    void anInterruptIsReportedAndTheFlagIsRestored() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Backoff.sleep(1)).isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear it, or every later test in this JVM inherits an interrupted thread.
            Thread.interrupted();
        }
    }

    /**
     * A large attempt number is still a legal delay.
     *
     * <p>The shift is capped at sixteen places and the result at twenty seconds. Without either, the
     * doubling would overflow to a negative for a big enough attempt, and {@code Thread.sleep} rejects
     * a negative delay with {@code IllegalArgumentException} — which the caller does not catch, so a
     * retry loop would die on the delay rather than on the provider. Asserting the exception type is
     * how that arithmetic is observed without waiting for it: the interrupt short-circuits the sleep,
     * but only after the delay has been computed and range-checked.
     */
    @Test
    void anAbsurdAttemptNumberStillComputesALegalDelay() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Backoff.sleep(Integer.MAX_VALUE)).isInstanceOf(EmbeddingException.class)
                    .isNotInstanceOf(IllegalArgumentException.class);
        } finally {
            Thread.interrupted();
        }
    }

    /** The first retry waits at least the base delay — full jitter randomises upwards, never below it. */
    @Test
    void theDelayIsNeverShorterThanTheBase() {
        long start = System.nanoTime();
        Backoff.sleep(0);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(250);
    }
}
