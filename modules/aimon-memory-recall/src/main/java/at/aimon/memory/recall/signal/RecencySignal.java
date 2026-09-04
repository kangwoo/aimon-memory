package at.aimon.memory.recall.signal;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential decay from the last time a fact was reinforced.
 *
 * <p>Measured from {@code last_reinforced_at}, not {@code created_at}. That single choice is what
 * makes forgetting selective rather than uniform: a fact that keeps coming up keeps having its clock
 * reset and never ages, while something mentioned once slides down the ranking on its own.
 *
 * <p><b>The formula is {@code 0.5^(Δdays / halfLife)}, not {@code exp(−Δdays / halfLife)}.</b> The
 * specification wrote the latter while calling the parameter a half-life, and the two are not the
 * same function: {@code exp(−1)} is 0.368, so under that form a "180-day half-life" actually halves
 * at 125 days and the setting means something other than what it says. Since this value is exposed as
 * workspace configuration for people to tune, it has to behave the way its name promises.
 */
public final class RecencySignal {

    private RecencySignal() {
    }

    public static double of(Instant lastReinforcedAt, Instant now, double halfLifeDays) {
        if (lastReinforcedAt == null || halfLifeDays <= 0) {
            return 1.0;
        }
        double elapsedDays = Duration.between(lastReinforcedAt, now).toMillis() / 86_400_000.0;
        if (elapsedDays <= 0) {
            return 1.0;
        }
        return Math.pow(0.5, elapsedDays / halfLifeDays);
    }
}
