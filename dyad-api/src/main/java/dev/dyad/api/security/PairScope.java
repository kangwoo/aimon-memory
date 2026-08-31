package dev.dyad.api.security;

import dev.dyad.core.key.PairKey;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code (observer, observed)} key a request names, refusing one the token does not own.
 *
 * <p>Every pair-scoped route takes observer and observed in the request body or the query string
 * rather than the path — {@code /recall}, {@code /conclusions}, {@code /chat}, {@code /dreams},
 * {@code /peer-card}. {@link AuthInterceptor} only sees path variables, so the route table checks
 * the workspace and nothing else: a peer token could name any pair in its workspace and read back
 * another peer's private conclusions. That is the whole product invariant, refuted by a request body.
 *
 * <p>The observer side is the one that has to match. A pair is the observer's memory, and holding
 * bob's token is not a claim on what alice remembers. The observed side is deliberately unchecked —
 * keeping a memory of someone is not a permission they grant.
 *
 * <p>{@code PairScopeTest} fails the build if a controller constructs a {@link PairKey} directly,
 * which is what keeps this from being forgotten the next time a route grows an observer parameter.
 */
@Component
public class PairScope {

    public PairKey of(DyadPrincipal principal, String workspace, String observer, String observed) {
        if (!principal.canReachPeer(observer)) {
            throw new ForbiddenException(
                    "token is not scoped to peer " + observer + "; it cannot reach that pair's memory");
        }
        return new PairKey(workspace, observer, observed);
    }
}
