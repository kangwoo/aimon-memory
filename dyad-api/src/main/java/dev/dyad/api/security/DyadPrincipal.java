package dev.dyad.api.security;

/**
 * The authenticated caller.
 *
 * @param allowMemberRead lets a session-scoped token read its own session's data. Off by default:
 *     the narrow token is the one handed to a browser, and a default that granted reads would make
 *     every such token a session-history export.
 */
public record DyadPrincipal(
        TokenScope scope, String workspace, String peer, String session, boolean allowMemberRead) {

    public static DyadPrincipal admin() {
        return new DyadPrincipal(TokenScope.ADMIN, null, null, null, false);
    }

    public boolean canReachWorkspace(String name) {
        return scope == TokenScope.ADMIN || name.equals(workspace);
    }

    public boolean canReachPeer(String name) {
        return switch (scope) {
            case ADMIN, WORKSPACE -> true;
            case PEER, SESSION -> name.equals(peer);
        };
    }

    public boolean canReachSession(String name) {
        return switch (scope) {
            case ADMIN, WORKSPACE, PEER -> true;
            case SESSION -> name.equals(session);
        };
    }

    /**
     * True when this token may write a message attributed to {@code name}.
     *
     * <p>Wider than {@link #canReachPeer} on purpose. A session token names a conversation rather
     * than a participant, and a conversation has several — transcribing all of them is the entire job
     * of the token handed to whatever is running the chat. A token that <em>does</em> name a peer is
     * that peer, and must not be able to sign someone else's name to a message: everything downstream
     * treats the speaker as established fact, fanning it out into every observer's memory and deriving
     * conclusions about them from it.
     */
    public boolean canSpeakAs(String name) {
        return peer == null || name.equals(peer);
    }
}
