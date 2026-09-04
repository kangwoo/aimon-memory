package at.aimon.memory.client;

/**
 * A call to the AIMON Memory service that did not produce an answer.
 *
 * <p>
 * One type for three different failures, because a caller of {@link at.aimon.core.memory.PeerMemory} can do the same
 * thing about all of them and nothing about any of them: the connection did not open, the service answered with a
 * status this adapter does not treat as a result, or the body was not what the API documents. What separates them is
 * {@link #getStatus()} and {@link #getCode()}, which are for a log line and an operator, not for control flow.
 *
 * <p>
 * Note what is <b>not</b> here: a 404 on a snapshot is not this. A peer with nothing recorded is an ordinary answer —
 * {@code Optional.empty()} — and turning it into an exception would make "no memory yet" indistinguishable from "the
 * memory service is down", which is the one distinction a caller deciding whether to degrade actually needs.
 */
public class RemoteMemoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Used when the failure happened before any status was received. */
    public static final int NO_STATUS = -1;

    private final int status;
    private final String code;

    public RemoteMemoryException(String message, Throwable cause) {
        super(message, cause);
        this.status = NO_STATUS;
        this.code = null;
    }

    public RemoteMemoryException(String message, int status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Returns the HTTP status the service answered with, or {@link #NO_STATUS} when the call never got one.
     *
     * @return the status code, or {@link #NO_STATUS}
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the service's own error code — {@code bad_scope}, {@code llm_not_configured} and so on.
     *
     * @return the stable error code, or null when the failure produced no response body
     */
    public String getCode() {
        return code;
    }
}
