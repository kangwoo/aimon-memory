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
 */
@Service
public class JwtService {

    private static final String ISSUER = "dyad";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration defaultLifetime;

    public JwtService(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new DyadException(
                    "weak_jwt_secret",
                    "dyad.jwt.secret must be at least " + MINIMUM_SECRET_BYTES + " bytes for HS256");
        }
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret);
        this.defaultLifetime = properties.lifetime();
    }

    public String issue(DyadPrincipal principal, Duration lifetime) {
        Instant now = Instant.now();
        Duration ttl = lifetime == null ? defaultLifetime : lifetime;

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

    private static String string(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : value.toString();
    }
}
