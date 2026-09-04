package dev.dyad.api.web;

import dev.dyad.api.dto.Dtos;
import dev.dyad.api.dto.Requests;
import dev.dyad.api.security.DyadPrincipal;
import dev.dyad.api.security.ForbiddenException;
import dev.dyad.api.security.JwtService;
import dev.dyad.api.security.TokenScope;
import dev.dyad.core.DyadException;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints scoped tokens.
 *
 * <p>The narrowing checks are here rather than in the route table because they depend on the body: a
 * token request has to name the workspace, peer or session it is for, and a scope that names nothing
 * is a workspace token wearing a session token's label.
 *
 * <p><b>A token may only mint something no wider than itself.</b> That is what makes the nesting in
 * {@link TokenScope} worth having: a service holding a workspace token can hand a client a token
 * for one session without holding an admin key, and the client cannot widen it back. The route table
 * keeps peer and session tokens out entirely — neither satisfies a workspace-scoped route — so the
 * only callers reaching here are admin and workspace, and a workspace caller is confined to its own
 * workspace by {@link DyadPrincipal#canReachWorkspace}.
 */
@RestController
public class TokenController {

    private final JwtService jwt;

    public TokenController(JwtService jwt) {
        this.jwt = jwt;
    }

    @PostMapping("/v1/tokens")
    public Dtos.TokenResponse issue(DyadPrincipal caller, @Valid @RequestBody Requests.IssueToken body) {
        TokenScope scope = TokenScope.fromWire(body.scope());
        requireNarrowing(scope, body);
        requireNoWiderThanCaller(caller, scope, body);

        DyadPrincipal principal =
                new DyadPrincipal(
                        scope,
                        body.workspace(),
                        body.peer(),
                        body.session(),
                        Boolean.TRUE.equals(body.allowMemberRead()));

        // Resolved before issuing rather than repeated as a literal: the reported expiry was a
        // hard-coded twelve hours, so configuring dyad.jwt.lifetime produced a token whose real exp
        // and whose advertised exp disagreed, and only the one nobody looks at was right.
        Duration lifetime =
                body.lifetimeSeconds() == null
                        ? jwt.defaultLifetime()
                        : Duration.ofSeconds(body.lifetimeSeconds());
        String token = jwt.issue(principal, lifetime);
        return new Dtos.TokenResponse(token, scope.wire(), Instant.now().plus(lifetime));
    }

    /**
     * Refuse anything the caller could not already do.
     *
     * <p>Three separate widenings to close, and missing any one of them turns delegation into
     * escalation: a broader scope than the caller holds, a different workspace, or — for a caller
     * that is itself a peer — a token naming someone else. The last is the subtle one: a token with
     * no peer speaks for every participant in its session, so minting one from a peer token would
     * launder the identity check that {@code canSpeakAs} performs on every message.
     */
    private static void requireNoWiderThanCaller(
            DyadPrincipal caller, TokenScope requested, Requests.IssueToken body) {
        if (!caller.scope().satisfies(requested)) {
            throw new ForbiddenException(
                    "a " + caller.scope().wire() + " token cannot mint a " + requested.wire() + " token");
        }
        if (body.workspace() != null && !caller.canReachWorkspace(body.workspace())) {
            throw new ForbiddenException("token is not scoped to workspace " + body.workspace());
        }
        if (caller.peer() != null && !caller.peer().equals(body.peer())) {
            throw new ForbiddenException(
                    "a token scoped to peer " + caller.peer() + " may only mint tokens for that peer");
        }
        if (caller.session() != null && !caller.session().equals(body.session())) {
            throw new ForbiddenException(
                    "a token scoped to session " + caller.session()
                            + " may only mint tokens for that session");
        }
    }

    private static void requireNarrowing(TokenScope scope, Requests.IssueToken body) {
        switch (scope) {
            case ADMIN -> {
                // Nothing to narrow.
            }
            case WORKSPACE -> require(body.workspace(), "workspace");
            case PEER -> {
                require(body.workspace(), "workspace");
                require(body.peer(), "peer");
            }
            case SESSION -> {
                require(body.workspace(), "workspace");
                require(body.session(), "session");
            }
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DyadException("bad_scope", field + " is required for this token scope");
        }
    }
}
