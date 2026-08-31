package dev.dyad.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider configuration for both runnable applications.
 *
 * <p>{@code provider = none} and {@code hashing} exist so the system starts without credentials.
 * They are development conveniences and say so: a hashing embedder produces vectors whose
 * similarities are real but meaningless, which is fine for exercising a code path and useless for
 * anything else.
 */
@ConfigurationProperties(prefix = "dyad")
public record DyadProperties(Llm llm, Embed embed, Text text) {

    public DyadProperties {
        llm = llm == null ? Llm.defaults() : llm;
        embed = embed == null ? Embed.defaults() : embed;
        text = text == null ? new Text(null) : text;
    }

    /**
     * @param provider {@code openai}, {@code anthropic} or {@code none}
     * @param fallbackProvider tried after the primary is exhausted; {@code none} to disable
     * @param attemptsPerProvider attempts before moving on — the plan is flat, so this never returns
     *     to a provider it has already given up on
     */
    public record Llm(
            String provider,
            String fallbackProvider,
            String openAiBaseUrl,
            String openAiApiKey,
            String openAiModel,
            String anthropicBaseUrl,
            String anthropicApiKey,
            String anthropicModel,
            int attemptsPerProvider,
            Duration timeout) {

        public Llm {
            provider = blankTo(provider, "none");
            fallbackProvider = blankTo(fallbackProvider, "none");
            openAiBaseUrl = blankTo(openAiBaseUrl, "https://api.openai.com/v1");
            openAiModel = blankTo(openAiModel, "gpt-4.1-mini");
            anthropicBaseUrl = blankTo(anthropicBaseUrl, "https://api.anthropic.com");
            anthropicModel = blankTo(anthropicModel, "claude-sonnet-4-5");
            attemptsPerProvider = attemptsPerProvider <= 0 ? 2 : attemptsPerProvider;
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        }

        static Llm defaults() {
            return new Llm(null, null, null, null, null, null, null, null, 0, null);
        }
    }

    /** @param provider {@code openai} or {@code hashing} */
    public record Embed(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int dimensions,
            int maxBatchSize,
            int maxInputTokens,
            int maxAttempts,
            Duration timeout) {

        public Embed {
            provider = blankTo(provider, "hashing");
            baseUrl = blankTo(baseUrl, "https://api.openai.com/v1");
            model = blankTo(model, "text-embedding-3-small");
            dimensions = dimensions <= 0 ? 1536 : dimensions;
            maxBatchSize = maxBatchSize <= 0 ? 96 : maxBatchSize;
            maxInputTokens = maxInputTokens <= 0 ? 8191 : maxInputTokens;
            maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        static Embed defaults() {
            return new Embed(null, null, null, null, 0, 0, 0, 0, null);
        }
    }

    /** @param koreanUserDictionary path to a Nori user dictionary; the fix for split proper nouns */
    public record Text(String koreanUserDictionary) {}

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
