package at.aimon.memory.api.security;

import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/**
 * Four nested authorities, narrowest last.
 *
 * <p>Nesting is what makes delegation safe: a service holding a workspace token can mint a session
 * token for one conversation and hand it to a client, and that client cannot widen it back. A flat
 * scheme forces either an all-powerful key on the client or a proxy in front of every call.
 */
public enum TokenScope {
    ADMIN(0), WORKSPACE(1), PEER(2), SESSION(3);

    private final int rank;

    TokenScope(int rank) {
        this.rank = rank;
    }

    /** True when this scope is at least as broad as the one required. */
    public boolean satisfies(TokenScope required) {
        return this.rank <= required.rank;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TokenScope fromWire(String wire) {
        for (TokenScope scope : values()) {
            if (scope.wire().equals(wire)) {
                return scope;
            }
        }
        throw new MemoryException("bad_scope", "unknown token scope: " + wire);
    }
}
