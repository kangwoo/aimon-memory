package at.aimon.memory.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import at.aimon.memory.api.security.JwtService;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.TokenScope;
import at.aimon.memory.testkit.db.PostgresSupport;

/** Boots the real application against a real database. Only the model provider is left unconfigured. */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
public abstract class ApiTestBase {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JwtService jwt;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("aimon.memory.jwt.secret", () -> "a-test-secret-that-is-long-enough-for-hs256");
        registry.add("spring.flyway.enabled", () -> "false");
        // One container, one pool per Spring context, and a test configuration that differs in any
        // property gets a context of its own. Twenty connections apiece — not Boot's default of ten but
        // `AIMON_MEMORY_DB_POOL` in this module's own `application.yml`, which a `@SpringBootTest` on
        // `ApiApplication` reads like any other run — exhausted Postgres's hundred as the sixth context
        // came up: `FATAL: sorry, too many clients already`, raised while `EmbeddingDimensionCheck` was
        // initialising, so three unrelated tests failed on a class none of them touch.
        //
        // Counted in `pg_stat_activity` while the tier runs, the total is a staircase of one step per
        // context above the sixteen the testkit's own pool holds. Five contexts reached 99 of the
        // hundred and passed, which is what makes the sixth the one that fell over rather than the
        // greedy one: there was no headroom left to take. Nothing here needs more than a few — MockMvc
        // drives one request at a time — and five apiece puts the same staircase at 47.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @BeforeEach
    void resetDatabase() {
        PostgresSupport.dataSource();
        PostgresSupport.truncateAll();
    }

    protected String adminToken() {
        return jwt.issue(MemoryPrincipal.admin(), null);
    }

    protected String workspaceToken(String workspace) {
        return jwt.issue(new MemoryPrincipal(TokenScope.WORKSPACE, workspace, null, null, false), null);
    }

    protected String peerToken(String workspace, String peer) {
        return jwt.issue(new MemoryPrincipal(TokenScope.PEER, workspace, peer, null, false), null);
    }

    protected String sessionToken(String workspace, String session, boolean allowMemberRead) {
        return jwt.issue(new MemoryPrincipal(TokenScope.SESSION, workspace, null, session, allowMemberRead), null);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
