package dev.dyad.api.web;

import dev.dyad.api.dto.Dtos;
import dev.dyad.api.dto.Requests;
import dev.dyad.api.security.DyadPrincipal;
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
 * Mints scoped tokens. Admin only.
 *
 * <p>The narrowing checks are here rather than in the route table because they depend on the body: a
 * token request has to name the workspace, peer or session it is for, and a scope that names nothing
 * is a workspace token wearing a session token's label.
 */
@RestController
public class TokenController {

    private final JwtService jwt;

    public TokenController(JwtService jwt) {
        this.jwt = jwt;
    }

    @PostMapping("/v1/tokens")
    public Dtos.TokenResponse issue(@Valid @RequestBody Requests.IssueToken body) {
        TokenScope scope = TokenScope.fromWire(body.scope());
        requireNarrowing(scope, body);

        DyadPrincipal principal =
                new DyadPrincipal(
                        scope,
                        body.workspace(),
                        body.peer(),
                        body.session(),
                        Boolean.TRUE.equals(body.allowMemberRead()));

        Duration lifetime =
                body.lifetimeSeconds() == null ? null : Duration.ofSeconds(body.lifetimeSeconds());
        String token = jwt.issue(principal, lifetime);
        return new Dtos.TokenResponse(
                token, scope.wire(), Instant.now().plus(lifetime == null ? Duration.ofHours(12) : lifetime));
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
