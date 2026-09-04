package dev.dyad.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dyad.api.security.DyadPrincipal;
import dev.dyad.api.security.JwtProperties;
import dev.dyad.api.security.JwtService;
import dev.dyad.api.security.TokenScope;
import dev.dyad.core.DyadException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The signing key is a startup condition, not a default.
 *
 * <p>A development secret in {@code application.yml} is a key published in the repository: a
 * deployment that forgets the environment variable starts cleanly and signs production tokens with
 * it, and nothing in the running system distinguishes that from a correct configuration. Refusing to
 * start is the only failure mode that cannot be missed.
 */
class JwtServiceTest {

    private static final String GOOD_SECRET = "a-test-secret-that-is-long-enough-for-hs256";

    @Test
    void aMissingSecretRefusesToStart() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties(null, null)))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("DYAD_JWT_SECRET");

        assertThatThrownBy(() -> new JwtService(new JwtProperties("   ", null)))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("DYAD_JWT_SECRET");
    }

    @Test
    void aShortSecretRefusesToStart() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", null)))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aTokenRoundTripsWithItsScope() {
        JwtService jwt = new JwtService(new JwtProperties(GOOD_SECRET, Duration.ofHours(1)));
        String token = jwt.issue(new DyadPrincipal(TokenScope.PEER, "ws", "alice", null, false), null);

        DyadPrincipal back = jwt.verify(token);
        assertThat(back.scope()).isEqualTo(TokenScope.PEER);
        assertThat(back.workspace()).isEqualTo("ws");
        assertThat(back.peer()).isEqualTo("alice");
    }

    /** There is no revocation list, so a lifetime is the only thing that ends a leaked token. */
    @Test
    void anEndlessLifetimeIsRefused() {
        JwtService jwt = new JwtService(new JwtProperties(GOOD_SECRET, null));

        assertThatThrownBy(() -> jwt.issue(DyadPrincipal.admin(), Duration.ofDays(3650)))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("30 days");
        assertThatThrownBy(() -> jwt.issue(DyadPrincipal.admin(), Duration.ZERO))
                .isInstanceOf(DyadException.class);
    }

    /**
     * A default lifetime above the cap is a startup failure, not a per-request one.
     *
     * <p>Enforced only at issue time, {@code dyad.jwt.lifetime: 60d} booted cleanly and then rejected
     * every {@code POST /v1/tokens} that omitted an explicit lifetime — the normal case — with a 400
     * blaming the request. Token minting was dead for the whole deployment, and nothing at startup
     * said so.
     */
    @Test
    void aDefaultLifetimeAboveTheCapRefusesToStart() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties(GOOD_SECRET, Duration.ofDays(60))))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("dyad.jwt.lifetime")
                .hasMessageContaining("30-day");

        assertThatThrownBy(() -> new JwtService(new JwtProperties(GOOD_SECRET, Duration.ZERO)))
                .isInstanceOf(DyadException.class)
                .hasMessageContaining("dyad.jwt.lifetime");

        // The boundary itself is configuration, not a mistake.
        assertThat(new JwtService(new JwtProperties(GOOD_SECRET, Duration.ofDays(30))).defaultLifetime())
                .isEqualTo(Duration.ofDays(30));
    }

    /** A token signed by another deployment's key is not merely wrong, it is unauthenticated. */
    @Test
    void aTokenFromAnotherKeyIsRejected() {
        JwtService mine = new JwtService(new JwtProperties(GOOD_SECRET, null));
        JwtService theirs =
                new JwtService(new JwtProperties("a-different-secret-that-is-also-long-enough", null));

        String token = theirs.issue(DyadPrincipal.admin(), null);
        assertThatThrownBy(() -> mine.verify(token)).isInstanceOf(DyadException.class);
    }
}
