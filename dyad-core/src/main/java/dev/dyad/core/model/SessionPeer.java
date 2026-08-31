package dev.dyad.core.model;

import java.time.Instant;

/**
 * Membership of a peer in a session, as a time window.
 *
 * <p>{@code leftAt} being null means "still in". The window matters because fan-out has to answer
 * "who was present when this message was sent", not "who is present now".
 */
public record SessionPeer(
        String workspaceName,
        String sessionName,
        String peerName,
        Boolean observeMe,
        Boolean observeOthers,
        Instant joinedAt,
        Instant leftAt) {

    public boolean wasPresentAt(Instant when) {
        if (joinedAt != null && when.isBefore(joinedAt)) {
            return false;
        }
        return leftAt == null || when.isBefore(leftAt);
    }
}
