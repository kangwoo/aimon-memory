package at.aimon.memory.core.model;

import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/** The audit vocabulary. Every write path to {@code conclusions} emits exactly one of these. */
public enum EventType {
    ADD, REINFORCE, REPLACE, DELETE, EXPIRE, RESTORE,
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
        throw new MemoryException("bad_event", "unknown event type: " + wire);
    }
}
