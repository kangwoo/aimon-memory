package dev.dyad.recall.signal;

/**
 * How often a fact has been re-derived, as a bounded signal.
 *
 * <p>{@code 1 - 1/(1 + ln(1 + n))}. Logarithmic because the difference between hearing something
 * once and twice is real, and the difference between the fortieth and forty-first time is not. It
 * never reaches 1, so a frequently repeated fact can outrank a rare one but cannot dominate the
 * semantic signal outright.
 *
 * <p>This number exists in one source system and a ranking engine exists in the other, and neither
 * connected them. It costs nothing here — the counter was already being maintained by dedup.
 */
public final class ReinforcementSignal {

    private ReinforcementSignal() {}

    public static double of(int timesDerived) {
        int n = Math.max(1, timesDerived);
        return 1.0 - 1.0 / (1.0 + Math.log(1.0 + n));
    }
}
