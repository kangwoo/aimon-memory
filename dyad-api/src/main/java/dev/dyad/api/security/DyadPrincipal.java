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
}
