package at.aimon.memory.core.key;

import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/** What a work unit asks the worker to do. Serialised as the first segment of a work unit key. */
public enum TaskType {
    /** Derive conclusions for one (observer, observed) pair from a batch of messages. */
    REPRESENTATION,
    /** Roll session messages into short/long summaries. */
    SUMMARY,
    /** Embedding sync, expiry sweep, queue cleanup. */
    RECONCILER,
    /** Cascade a delete through conclusions, links and orphan entities. */
    DELETION,
    /** Autonomous reasoning pass over a pair's conclusions. */
    DREAM,
    /** Cheap dream variant that only rewrites the peer card. */
    CARD_REFRESH;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskType fromWire(String wire) {
        for (TaskType t : values()) {
            if (t.wire().equals(wire)) {
                return t;
            }
        }
        throw new MemoryException("bad_key", "unknown task type: " + wire);
    }
}
