package at.aimon.memory.core.model;

import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/** Who changed a conclusion. Recorded on every event so autonomous edits stay attributable. */
public enum Actor {
    DERIVER, DREAMER, DEDUP, API, RECONCILER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Actor fromWire(String wire) {
        for (Actor a : values()) {
            if (a.wire().equals(wire)) {
                return a;
            }
        }
        throw new MemoryException("bad_actor", "unknown actor: " + wire);
    }
}
