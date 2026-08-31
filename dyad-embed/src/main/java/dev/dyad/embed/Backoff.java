package dev.dyad.embed;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter.
 *
 * <p>Full jitter rather than a fixed multiplier: when a provider rate-limits a worker fleet, every
 * worker backs off on the same schedule and retries in the same instant unless the delay is
 * randomised. The thundering herd is the actual failure mode, not the first 429.
 */
final class Backoff {

    private static final long BASE_MILLIS = 250;
    private static final long CAP_MILLIS = 20_000;

    private Backoff() {}

    static void sleep(int attempt) {
        long ceiling = Math.min(CAP_MILLIS, BASE_MILLIS << Math.min(attempt, 16));
        long delay = ThreadLocalRandom.current().nextLong(BASE_MILLIS, ceiling + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("interrupted while backing off", e);
        }
    }
}
