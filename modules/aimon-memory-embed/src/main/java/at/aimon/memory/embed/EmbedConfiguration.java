package at.aimon.memory.embed;

import java.util.Locale;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.Embedder;

/**
 * The one {@link Embedder} bean, defined in the module that owns the implementations.
 *
 * <p>Anything that needs an embedder imports this and stops caring which one it got. That is the
 * point: {@code aimon-memory-recall} needs an {@code Embedder} and sits below
 * {@code aimon-memory-engine}, where this bean used to be defined — so recall's Spring wiring
 * depended on a module above it, and the coordinate the BOM publishes for recall could not be
 * assembled on its own.
 */
@Configuration
@EnableConfigurationProperties(EmbedProperties.class)
public class EmbedConfiguration {

    /**
     * Fails at startup on a provider name this module does not implement.
     *
     * <p>The previous shape was {@code if ("openai".equals(name)) … else new HashingEmbedder(…)}, and
     * the else branch is what makes that a defect rather than a default: {@code openal} is a typo
     * that starts cleanly and serves lexical vectors out of a production semantic index. The failure
     * has no symptom at the boundary — recall answers, the scores are real numbers, and only the
     * quality is wrong — which is the kind that gets diagnosed by bisecting ranking results. A
     * missing API key already stops the process here; an unusable provider name is the same class of
     * mistake and now gets the same treatment.
     *
     * <p>{@code hashing} stays the default because the system has to start without credentials. It is
     * a development stand-in and {@link HashingEmbedder} says so.
     */
    @Bean
    public Embedder embedder(EmbedProperties properties) {
        String provider = properties.provider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "openai" -> new OpenAiEmbedder(
                    properties.toEmbeddingProperties(require(properties.apiKey(), "aimon.memory.embed.api-key")));
            case "hashing" -> new HashingEmbedder(properties.dimensions());
            default -> throw new MemoryException("unknown_embed_provider",
                    "aimon.memory.embed.provider is '" + properties.provider() + "', which this build does not "
                            + "implement. Known providers: openai, hashing. Startup fails rather than falling back "
                            + "to hashing, because a hashing embedder in production scores lexical overlap and "
                            + "nothing else — the failure would show up as bad ranking, not as an error.");
        };
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new MemoryException("missing_config", property + " is required for this provider");
        }
        return value;
    }
}
