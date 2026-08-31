package dev.dyad.core.model;

import dev.dyad.core.DyadException;
import java.util.Locale;

/** Who changed a conclusion. Recorded on every event so autonomous edits stay attributable. */
public enum Actor {
    DERIVER,
    DREAMER,
    DEDUP,
    API,
    RECONCILER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Actor fromWire(String wire) {
        for (Actor a : values()) {
            if (a.wire().equals(wire)) {
                return a;
            }
        }
        throw new DyadException("bad_actor", "unknown actor: " + wire);
    }
}
