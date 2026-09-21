package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import at.aimon.memory.testkit.db.PostgresSupport;

/**
 * The application migrates its own schema on start.
 *
 * <p>This is the one property of Flyway in this build that no other test covers, and it was broken
 * for the length of a release without anything going red. Boot 4 split the auto-configurations out
 * of {@code spring-boot-autoconfigure} into a module per technology, so {@code FlywayAutoConfiguration}
 * stopped arriving with {@code org.flywaydb:flyway-core}. {@code spring.flyway.enabled: true} in
 * {@code application.yml} then had nothing to act on: the process started, reported UP, and answered
 * the first write with {@code relation "workspaces" does not exist}.
 *
 * <p>Two things hid it, and this test is written around both. {@link PostgresSupport} runs
 * {@code Flyway.configure()} itself, so every test in the suite gets a migrated schema whether or not
 * Boot would have produced one; and both {@code @SpringBootTest} bases then set
 * {@code spring.flyway.enabled=false}, because the schema is already there. Between them, the entire
 * Docker tier passes against an application that cannot create its own database. So this class turns
 * Flyway back on and asserts on the bean — the assertion has to be about what <b>Boot</b> wired, not
 * about whether the tables exist.
 *
 * <p>{@code info().applied()} rather than merely a non-null bean: it also pins that the bean took
 * this module's {@code locations} and placeholders, which is what a bean built from the wrong
 * configuration would not have.
 */
@SpringBootTest(classes = ApiApplication.class)
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class FlywayAutoConfigurationTest {

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("aimon.memory.jwt.secret", () -> "a-test-secret-that-is-long-enough-for-hs256");
        // The point of this context. Every other one in the module turns it off.
        registry.add("spring.flyway.enabled", () -> "true");
        // See `ApiTestBase`: one pool per context, and Postgres's hundred connections are shared.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @Test
    void bootWiresFlywayFromThisModulesConfiguration() {
        assertThat(flyway).isNotNull();
        assertThat(flyway.info().applied()).isNotEmpty();
    }
}
