package at.aimon.memory.api.web;

import java.time.Duration;
import java.time.Instant;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.dto.Requests;
import at.aimon.memory.api.security.ForbiddenException;
import at.aimon.memory.api.security.JwtService;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.TokenScope;
import at.aimon.memory.core.MemoryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
 * workspace by {@link MemoryPrincipal#canReachWorkspace}.
 */
@RestController
@Tag(name = "tokens", description = "Minting scoped tokens. A token may mint none wider than itself.")
public class TokenController {

    private final JwtService jwt;

    public TokenController(JwtService jwt) {
        this.jwt = jwt;
    }

    @Operation(summary = "Mint a scoped token")
    @PostMapping("/v1/tokens")
    public Dtos.TokenResponse issue(MemoryPrincipal caller, @Valid @RequestBody Requests.IssueToken body) {
        TokenScope scope = TokenScope.fromWire(body.scope());
        requireNarrowing(scope, body);
        requireNoWiderThanCaller(caller, scope, body);

        MemoryPrincipal principal = new MemoryPrincipal(scope, body.workspace(), body.peer(), body.session(),
                Boolean.TRUE.equals(body.allowMemberRead()));

        // Resolved before issuing rather than repeated as a literal: the reported expiry was a
        // hard-coded twelve hours, so configuring aimon.memory.jwt.lifetime produced a token whose real exp
        // and whose advertised exp disagreed, and only the one nobody looks at was right.
        Duration lifetime = body.lifetimeSeconds() == null
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
    private static void requireNoWiderThanCaller(MemoryPrincipal caller, TokenScope requested,
            Requests.IssueToken body) {
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
                    "a token scoped to session " + caller.session() + " may only mint tokens for that session");
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
            // Unreachable today: the four cases above are every TokenScope there is. It is here because a
            // statement switch does not make the compiler say so, and the failure it guards is silent —
            // a fifth scope added later would fall straight through, narrowing nothing, and mint a token
            // wider than its label claims.
            default -> throw new IllegalStateException("unhandled token scope: " + scope);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MemoryException("bad_scope", field + " is required for this token scope");
        }
    }
}
