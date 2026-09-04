package at.aimon.memory.core.model;

import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/**
 * How a conclusion came to exist, and how much the ranker trusts it.
 *
 * <p>The weight is a ranking signal in its own right ({@code lvl} in the fusion formula), not a
 * filter — a contradiction still surfaces, it just sits lower than something the user said outright.
 */
public enum ConclusionLevel {
    /** Stated in a message. Requires a session. */
    EXPLICIT(1.0),
    /** Follows necessarily from other conclusions. */
    DEDUCTIVE(0.9),
    /** A pattern over other conclusions; carries a confidence. */
    INDUCTIVE(0.8),
    /** Two conclusions that cannot both hold. */
    CONTRADICTION(0.6);

    private final double rankWeight;

    ConclusionLevel(double rankWeight) {
        this.rankWeight = rankWeight;
    }

    /** The {@code lvl} signal, already normalised to [0,1]. */
    public double rankWeight() {
        return rankWeight;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConclusionLevel fromWire(String wire) {
        for (ConclusionLevel l : values()) {
            if (l.wire().equals(wire)) {
                return l;
            }
        }
        throw new MemoryException("bad_level", "unknown conclusion level: " + wire);
    }
}
