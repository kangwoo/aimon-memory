package at.aimon.memory.core.config;

import java.time.Duration;

/**
 * When a queued batch becomes eligible for work. Any one of the three suffices.
 *
 * <p>{@code idleFlush} is the addition that makes the system usable in a conversation: people speak,
 * then pause, and the pause is when the batch should go. Under load the token threshold fires first,
 * so the batching win is not given away.
 */
public record BatchSettings(int tokenThreshold, Duration maxAge, Duration idleFlush) {

    public static final BatchSettings DEFAULT = new BatchSettings(512, Duration.ofMinutes(30), Duration.ofSeconds(3));
}
