package dev.dyad.core.model;

import dev.dyad.core.DyadException;
import java.util.Locale;

/** Whether a conclusion's embedding is current. The reconciler drives rows back to {@link #SYNCED}. */
public enum SyncState {
    PENDING,
    SYNCED,
    FAILED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SyncState fromWire(String wire) {
        for (SyncState s : values()) {
            if (s.wire().equals(wire)) {
                return s;
            }
        }
        throw new DyadException("bad_sync_state", "unknown sync state: " + wire);
    }
}
