package at.aimon.memory.store;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import at.aimon.memory.text.AnalyzerRegistry;

/**
 * Wiring for the persistence layer. Imported by everything above it that needs a repository.
 *
 * <p>Two beans have to come from the importer, and the difference between them is the whole reason
 * this class grew a body. A {@code JdbcClient} and its {@code DataSource} are the application's to
 * choose. An {@code Embedder} is required by {@link EmbeddingDimensionCheck} and is named through
 * {@code core.spi}, not through an implementation — this module must not depend on
 * {@code aimon-memory-embed} to run a startup check, so it asks for the interface and lets whoever
 * assembles it supply one. {@code RecallConfiguration} is the lowest configuration that does.
 *
 * <p>{@code AnalyzerRegistry} was in that category by accident rather than by design, and is not any
 * more — see below.
 */
@Configuration
@ComponentScan(basePackages = "at.aimon.memory.store")
@EnableConfigurationProperties(TextProperties.class)
// `proxyTargetClass = true` rather than the annotation's default of JDK interface proxies. Spring
// Boot's own transaction auto-configuration sets this, so every deployment of this system already ran
// with it — which is precisely why it had to be stated here. Imported into a plain
// `AnnotationConfigApplicationContext` the default applied instead, `ConclusionRepository` came back
// as a `$Proxy` of `ConclusionStore`, and any injection by concrete type failed with a message about
// a type mismatch rather than about proxying. A published module that behaves one way inside a Boot
// application and another way outside one is not really publishable; this makes the choice the
// module's own.
@EnableTransactionManagement(proxyTargetClass = true)
public class StoreConfiguration {

    /**
     * The analyzer registry, defined here because {@link WorkspaceSettingsService} — a bean of this
     * module — requires one.
     *
     * <p>It used to be a {@code @Bean} in {@code MemoryConfiguration}, two modules up, which meant
     * importing {@code StoreConfiguration} was not enough to get a working store: the component scan
     * produced a {@code WorkspaceSettingsService} whose constructor argument nothing in this module
     * or below it could supply. Every assembly of this system happened to go through
     * {@code aimon-memory-engine}, so the gap never showed up at runtime — it showed up as
     * `aimon-memory-store` and `aimon-memory-recall` being published coordinates that could not be
     * wired on their own.
     *
     * <p>{@code AnalyzerRegistry} is {@code AutoCloseable} and Spring infers {@code close()} as the
     * destroy method, which is what closes the Lucene analyzers it caches.
     */
    @Bean
    public AnalyzerRegistry analyzerRegistry(TextProperties properties) {
        String dictionary = properties.koreanUserDictionary();
        return new AnalyzerRegistry(dictionary == null || dictionary.isBlank() ? null : Path.of(dictionary));
    }
}
