package dev.dyad.api;

import dev.dyad.api.security.DyadPrincipal;
import dev.dyad.api.security.JwtService;
import dev.dyad.api.security.TokenScope;
import dev.dyad.testkit.db.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the real application against a real database. Only the model provider is left unconfigured. */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtService jwt;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("dyad.jwt.secret", () -> "a-test-secret-that-is-long-enough-for-hs256");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void resetDatabase() {
        PostgresSupport.dataSource();
        PostgresSupport.truncateAll();
    }

    protected String adminToken() {
        return jwt.issue(DyadPrincipal.admin(), null);
    }

    protected String workspaceToken(String workspace) {
        return jwt.issue(new DyadPrincipal(TokenScope.WORKSPACE, workspace, null, null, false), null);
    }

    protected String peerToken(String workspace, String peer) {
        return jwt.issue(new DyadPrincipal(TokenScope.PEER, workspace, peer, null, false), null);
    }

    protected String sessionToken(String workspace, String session, boolean allowMemberRead) {
        return jwt.issue(
                new DyadPrincipal(TokenScope.SESSION, workspace, null, session, allowMemberRead), null);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
