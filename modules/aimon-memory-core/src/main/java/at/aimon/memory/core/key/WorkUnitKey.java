package at.aimon.memory.core.key;

import java.util.Optional;

import at.aimon.memory.core.MemoryException;

/**
 * Serialisation key for the work queue.
 *
 * <p>Two work units with the same key must never run concurrently — the queue enforces that by
 * claiming on this string. Everything that determines "is this the same stream of work" therefore
 * has to be in the key: the task, the workspace, the session (batches are per-session) and the pair.
 *
 * <p>Session is absent for pair-wide tasks such as {@link TaskType#DREAM}.
 */
public record WorkUnitKey(TaskType taskType, String workspaceName, String sessionName, String observer,
        String observed) {

    public WorkUnitKey {
        if (taskType == null) {
            throw new MemoryException("bad_key", "taskType must not be null");
        }
        Segments.required(workspaceName, "workspaceName");
        Segments.required(observer, "observer");
        Segments.required(observed, "observed");
        if (sessionName != null && sessionName.isBlank()) {
            sessionName = null;
        }
    }

    public static WorkUnitKey representation(String workspace, String session, PairKey pair) {
        return new WorkUnitKey(TaskType.REPRESENTATION, workspace, session, pair.observer(), pair.observed());
    }

    public static WorkUnitKey summary(String workspace, String session) {
        return new WorkUnitKey(TaskType.SUMMARY, workspace, session, session, session);
    }

    public static WorkUnitKey dream(PairKey pair) {
        return new WorkUnitKey(TaskType.DREAM, pair.workspaceName(), null, pair.observer(), pair.observed());
    }

    /**
     * The card refresh unit for a pair.
     *
     * <p>Here rather than spelled out at each call site so a caller has a way to name the unit that
     * takes an already-resolved {@link PairKey}. A controller assembling one from its own request
     * parameters has built a pair scope nothing checked, which is the hole {@code PairScope} and its
     * ArchUnit gate exist to close — and a five-argument constructor is exactly how that reappears.
     */
    public static WorkUnitKey cardRefresh(PairKey pair) {
        return new WorkUnitKey(TaskType.CARD_REFRESH, pair.workspaceName(), null, pair.observer(), pair.observed());
    }

    public PairKey pair() {
        return new PairKey(workspaceName, observer, observed);
    }

    public Optional<String> session() {
        return Optional.ofNullable(sessionName);
    }

    public String encode() {
        return taskType.wire() + ':' + Segments.encode(workspaceName) + ':' + Segments.encode(sessionName) + ':'
                + Segments.encode(observer) + ':' + Segments.encode(observed);
    }

    public static WorkUnitKey parse(String encoded) {
        String[] parts = encoded.split(":", -1);
        if (parts.length != 5) {
            throw new MemoryException("bad_key",
                    "work unit key needs 5 segments, got " + parts.length + ": " + encoded);
        }
        String session = Segments.decode(parts[2]);
        return new WorkUnitKey(TaskType.fromWire(parts[0]), Segments.decode(parts[1]),
                session.isEmpty() ? null : session, Segments.decode(parts[3]), Segments.decode(parts[4]));
    }

    @Override
    public String toString() {
        return encode();
    }
}
