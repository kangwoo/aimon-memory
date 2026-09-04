package at.aimon.memory.embed;

import java.time.Duration;

/**
 * Embedding provider configuration.
 *
 * @param dimensions 1536 by default — the same size both source systems use, so an existing corpus
 *     can be moved across without paying to re-embed it
 * @param maxBatchSize inputs per request; providers cap this and exceeding it fails the whole batch
 * @param maxInputTokens per-input cap; longer text is truncated rather than rejected
 */
public record EmbeddingProperties(String baseUrl, String apiKey, String model, int dimensions, int maxBatchSize,
        int maxInputTokens, int maxAttempts, Duration timeout) {

    public static EmbeddingProperties openAiDefaults(String apiKey) {
        return new EmbeddingProperties("https://api.openai.com/v1", apiKey, "text-embedding-3-small", 1536, 96, 8191, 4,
                Duration.ofSeconds(30));
    }
}
