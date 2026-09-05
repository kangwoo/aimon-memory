package at.aimon.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.spi.Embedder;

/**
 * Tier 1 assembles from this module and the ones below it, with nothing from above.
 *
 * <p>This is the regression test for the one dependency inversion this system actually had.
 * {@code RecallService} is a {@code @Service} that needs an {@code Embedder}, and until
 * {@link RecallConfiguration} existed neither the component scan that finds it nor the bean that
 * satisfies it lived at or below this module — both were in {@code MemoryConfiguration}, two layers
 * up in {@code aimon-memory-engine}. Compile-time the layering was clean and
 * {@code ModuleDependencyTest} confirmed it; the inversion was in the assembly graph, where ArchUnit
 * cannot look. The visible cost was that {@code aimon-memory-recall}, a coordinate in the BOM, could
 * not be wired by anyone who took it on its own.
 *
 * <p>So the assertion is deliberately about what is <em>absent</em>: this context is built from
 * {@code RecallConfiguration} alone, and if any bean it needs drifts back up into engine this fails
 * with a missing-bean error naming it. Putting this in {@code aimon-memory-recall}'s own test source
 * set is load-bearing — engine is not on this module's classpath, so the test cannot accidentally
 * pass by finding a bean there.
 *
 * <p>Untagged, so it runs in {@code checkAll}. Every other context test in this build is
 * {@code @Tag("docker")}, which is why a broken wiring could reach a release with the fast gate
 * green. Lazy initialisation is what removes the database: bean definitions are all resolved, and
 * only the ones reached from {@code RecallService} are instantiated, so nothing opens a connection
 * and {@code EmbeddingDimensionCheck} — which would query on {@code @PostConstruct} — is never
 * built. What is under test is whether the graph can be satisfied, not whether Postgres answers.
 */
class RecallConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
            .withUserConfiguration(ApplicationSuppliedBeans.class, RecallConfiguration.class);

    @Test
    void tierOneAssemblesWithoutTheEngineModule() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RecallService.class)).isNotNull();
            assertThat(ctx.getBean(ProvenanceService.class)).isNotNull();
        });
    }

    /**
     * The {@code Embedder} arrives from {@code aimon-memory-embed}, not from a stub in this test.
     *
     * <p>Asserted separately because it is the bean whose absence caused the problem, and because a
     * test that supplied its own would prove the opposite of what this file is for.
     */
    @Test
    void theEmbedderComesFromTheModulesBelowThisOne() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Embedder.class).getClass().getPackageName()).isEqualTo("at.aimon.memory.embed");
            assertThat(ctx.getBean(Clock.class)).isNotNull();
        });
    }

    /**
     * The two beans an application owns rather than this module: a {@code JdbcClient} and the
     * {@code DataSource} behind it. Everything else has to come from {@code RecallConfiguration}, or
     * the point of the test is lost.
     */
    @Configuration
    static class ApplicationSuppliedBeans {

        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }
    }
}
