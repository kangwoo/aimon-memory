package at.aimon.memory.embed;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which embedder a deployment gets, and how it is configured.
 *
 * <p>Moved here from {@code MemoryProperties.Embed} in aimon-memory-engine, along with the bean it
 * feeds. The settings and the choice they drive now live in the module that owns both embedder
 * implementations, which is what lets everything above it ask for an {@code Embedder} without also
 * having to know how to build one.
 *
 * @param provider {@code openai} or {@code hashing}. Anything else fails at startup rather than
 *     quietly selecting a default — see {@link EmbedConfiguration#embedder}
 * @param dimensions 1536 by default — the same size both source systems use, so an existing corpus
 *     can be moved across without paying to re-embed it. It is also the width the vector columns are
 *     created with, so changing it is a migration
 */
@ConfigurationProperties(prefix = "aimon.memory.embed")
public record EmbedProperties(String provider, String baseUrl, String apiKey, String model, int dimensions,
        int maxBatchSize, int maxInputTokens, int maxAttempts, Duration timeout) {

    public EmbedProperties {
        provider = provider == null || provider.isBlank() ? "hashing" : provider;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        dimensions = dimensions <= 0 ? 1536 : dimensions;
        maxBatchSize = maxBatchSize <= 0 ? 96 : maxBatchSize;
        maxInputTokens = maxInputTokens <= 0 ? 8191 : maxInputTokens;
        maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    /** The provider-shaped view {@link OpenAiEmbedder} takes. */
    public EmbeddingProperties toEmbeddingProperties(String apiKeyValue) {
        return new EmbeddingProperties(baseUrl, apiKeyValue, model, dimensions, maxBatchSize, maxInputTokens,
                maxAttempts, timeout);
    }
}
