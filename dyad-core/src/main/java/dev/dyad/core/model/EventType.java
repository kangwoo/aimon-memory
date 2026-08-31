package dev.dyad.core.model;

import dev.dyad.core.DyadException;
import java.util.Locale;

/** The audit vocabulary. Every write path to {@code conclusions} emits exactly one of these. */
public enum EventType {
    ADD,
    REINFORCE,
    REPLACE,
    DELETE,
    EXPIRE,
    RESTORE,
    /** The embedding call failed, so the row is invisible to semantic recall until it is retried. */
    SYNC_FAILED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EventType fromWire(String wire) {
        for (EventType e : values()) {
            if (e.wire().equals(wire)) {
                return e;
            }
        }
        throw new DyadException("bad_event", "unknown event type: " + wire);
    }
}
