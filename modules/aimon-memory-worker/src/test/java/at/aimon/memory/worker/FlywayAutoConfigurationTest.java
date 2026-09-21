package at.aimon.memory.worker;

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
 * The worker migrates its own schema on start, for the reason {@link WorkerApplicationTest} gives
 * for existing at all: the two applications share a codebase but not a context, and a dependency the
 * API declares is not thereby the worker's.
 *
 * <p>Both declare {@code spring.flyway.enabled: true} and both were missing the module that makes
 * Boot act on it. Which of the two processes reaches an empty database first is a deployment
 * ordering detail, so both have to be able to. The API's copy of this test carries the full account.
 */
@SpringBootTest(classes = WorkerApplication.class)
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
        // The point of this context. Every other one in the module turns it off.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @Test
    void bootWiresFlywayFromThisModulesConfiguration() {
        assertThat(flyway).isNotNull();
        assertThat(flyway.info().applied()).isNotEmpty();
    }
}
