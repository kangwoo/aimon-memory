package at.aimon.memory.recall;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import at.aimon.memory.embed.EmbedConfiguration;
import at.aimon.memory.store.StoreConfiguration;

/**
 * Tier 1, assembled from the modules below it and nothing above.
 *
 * <p>This did not exist, and its absence was the one place where this system's dependency direction
 * was genuinely inverted. {@code RecallService} and {@code ProvenanceService} are {@code @Service}
 * beans, but no configuration in this module scanned for them and no module at or below this one
 * defined the {@code Embedder} they need — {@code MemoryConfiguration}, in
 * {@code aimon-memory-engine}, did both. Compile-time the layering was clean and
 * {@code ModuleDependencyTest} said so; at assembly time recall depended on a module two layers up,
 * where ArchUnit cannot see. What it cost was concrete: {@code aimon-memory-recall} is a coordinate
 * in the BOM, and taking it on its own got you two services nothing would instantiate.
 *
 * <p>So the rule this file exists to hold: <b>a published module wires what it owns.</b> Importing
 * this is enough to use Tier 1, in this build or in someone else's.
 *
 * <p>{@code Clock} is defined here rather than in engine for the same reason — recall is the lowest
 * module that needs one, so it is the one that has to be able to supply it. Engine gets it by
 * importing this, which is why it no longer declares its own.
 */
@Configuration
@Import({StoreConfiguration.class, EmbedConfiguration.class})
@ComponentScan(basePackages = "at.aimon.memory.recall")
public class RecallConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
