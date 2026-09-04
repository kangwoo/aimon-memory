package dev.dyad.api.security;

import dev.dyad.core.DyadException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the four-tier tokens.
 *
 * <p>{@code exp} is written as a numeric date, per RFC 7519. Some libraries will happily emit it as a
 * string and some verifiers will then treat the claim as absent — a token that never expires, which
 * fails open and is invisible until someone looks at a decoded payload.
 *
 * <p><b>There is no default signing key.</b> An unset secret stops the application from starting.
 * The alternative — a development default in {@code application.yml} — is a key published in the
 * repository: any deployment that forgets the environment variable boots successfully, signs
 * production tokens with it, and anyone who has read the source can mint an admin token. Nothing in
 * the running system distinguishes that state from a correctly configured one, which is why it has
 * to be refused at startup rather than warned about.
 */
@Service
public class JwtService {

    private static final String ISSUER = "dyad";
    private static final int MINIMUM_SECRET_BYTES = 32;

    /**
     * Upper bound on a token's lifetime.
     *
     * <p>There is no revocation list, so a lifetime is the only thing that ends a leaked token.
     * Thirty days is already generous for something that cannot be recalled; without a cap a single
     * request could mint one that outlives the deployment.
     */
    public static final Duration MAX_LIFETIME = Duration.ofDays(30);

    private final SecretKey key;
    private final Duration defaultLifetime;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new DyadException(
                    "missing_config",
                    "dyad.jwt.secret is not set. Export DYAD_JWT_SECRET with at least "
                            + MINIMUM_SECRET_BYTES
                            + " bytes of random data (openssl rand -base64 48) before starting.");
        }
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new DyadException(
                    "weak_jwt_secret",
                    "dyad.jwt.secret must be at least " + MINIMUM_SECRET_BYTES + " bytes for HS256");
        }
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret);
        // The configured default is checked here, not only when a token is minted.
        //
        // MAX_LIFETIME was enforced at issue time alone, so dyad.jwt.lifetime: 60d booted cleanly and
        // then failed every POST /v1/tokens that omitted lifetimeSeconds — the normal case, which
        // resolves to this default — with a 400 blaming the request. Token minting was dead for the
        // whole deployment, with nothing said at startup and the error pointing at the wrong party.
        // The argument this class already makes for refusing a missing secret applies unchanged.
        if (properties.lifetime() == null
                || properties.lifetime().isNegative()
                || properties.lifetime().isZero()) {
            throw new DyadException(
                    "missing_config", "dyad.jwt.lifetime must be a positive duration");
        }
        if (properties.lifetime().compareTo(MAX_LIFETIME) > 0) {
            throw new DyadException(
                    "bad_config",
                    "dyad.jwt.lifetime is "
                            + properties.lifetime()
                            + ", above the "
                            + MAX_LIFETIME.toDays()
                            + "-day maximum. Every token minted without an explicit lifetime would be"
                            + " rejected at issue time; there is no revocation list, so expiry is the"
                            + " only thing that ends a leaked token.");
        }
        this.defaultLifetime = properties.lifetime();
    }

    public String issue(DyadPrincipal principal, Duration lifetime) {
        Instant now = Instant.now();
        Duration ttl = lifetime == null ? defaultLifetime : lifetime;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new DyadException("bad_lifetime", "a token lifetime must be positive");
        }
        if (ttl.compareTo(MAX_LIFETIME) > 0) {
            throw new DyadException(
                    "bad_lifetime",
                    "a token lifetime may not exceed " + MAX_LIFETIME.toDays() + " days; there is no"
                            + " revocation list, so expiry is the only thing that ends a leaked token");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("scope", principal.scope().wire());
        if (principal.workspace() != null) {
            claims.put("ws", principal.workspace());
        }
        if (principal.peer() != null) {
            claims.put("peer", principal.peer());
        }
        if (principal.session() != null) {
            claims.put("sess", principal.session());
        }
        if (principal.allowMemberRead()) {
            claims.put("amr", true);
        }

        return Jwts.builder()
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claims(claims)
                .signWith(key)
                .compact();
    }

    public DyadPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser().requireIssuer(ISSUER).verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getExpiration() == null) {
                throw new UnauthorizedException("token has no expiry");
            }
            return new DyadPrincipal(
                    TokenScope.fromWire(String.valueOf(claims.get("scope"))),
                    string(claims, "ws"),
                    string(claims, "peer"),
                    string(claims, "sess"),
                    Boolean.TRUE.equals(claims.get("amr")));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("token rejected: " + e.getMessage());
        }
    }

    /** The lifetime an issue call gets when it names none. */
    public Duration defaultLifetime() {
        return defaultLifetime;
    }

    private static String string(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : value.toString();
    }
}
