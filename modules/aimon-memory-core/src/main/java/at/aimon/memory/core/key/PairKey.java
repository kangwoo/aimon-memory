package at.aimon.memory.core.key;

import at.aimon.memory.core.MemoryException;

/**
 * The (observer, observed) pair inside a workspace — the scope every conclusion is filed under.
 *
 * <p>This is the one decision the whole system is built on: memory is never global, it belongs to a
 * directed pair. {@code alice observing alice} and {@code bot observing alice} are separate stores
 * that never leak into one another.
 */
public record PairKey(String workspaceName, String observer, String observed) {

    public PairKey {
        Segments.required(workspaceName, "workspaceName");
        Segments.required(observer, "observer");
        Segments.required(observed, "observed");
    }

    /** The pair a peer forms with itself — what {@code observe_me} produces. */
    public static PairKey self(String workspaceName, String peer) {
        return new PairKey(workspaceName, peer, peer);
    }

    public boolean isSelfPair() {
        return observer.equals(observed);
    }

    public String encode() {
        return Segments.encode(workspaceName) + ':' + Segments.encode(observer) + ':' + Segments.encode(observed);
    }

    public static PairKey parse(String encoded) {
        String[] parts = encoded.split(":", -1);
        if (parts.length != 3) {
            throw new MemoryException("bad_key", "pair key needs 3 segments, got " + parts.length + ": " + encoded);
        }
        return new PairKey(Segments.decode(parts[0]), Segments.decode(parts[1]), Segments.decode(parts[2]));
    }

    @Override
    public String toString() {
        return encode();
    }
}
