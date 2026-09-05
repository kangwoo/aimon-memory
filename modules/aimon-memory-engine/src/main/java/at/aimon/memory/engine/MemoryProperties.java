package at.aimon.memory.engine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat provider configuration, for both runnable applications.
 *
 * <p>{@code provider = none} exists so the system starts without credentials — a replay-only
 * deployment is legitimate and is how CI runs. It is a decision rather than a fallback: a name this
 * build does not implement fails at startup instead of being read as {@code none}.
 *
 * <p>This used to carry {@code embed} and {@code text} as well. They moved to the modules that own
 * the beans they feed — {@code at.aimon.memory.embed.EmbedProperties} and
 * {@code at.aimon.memory.store.TextProperties} — so that those modules can be assembled without this
 * one. The property prefixes did not change, so no deployment's configuration moved with them.
 */
@ConfigurationProperties(prefix = "aimon.memory")
public record MemoryProperties(Llm llm) {

    public MemoryProperties {
        llm = llm == null ? Llm.defaults() : llm;
    }

    /**
     * @param provider {@code openai}, {@code anthropic} or {@code none}
     * @param fallbackProvider tried after the primary is exhausted; {@code none} to disable
     * @param attemptsPerProvider attempts before moving on — the plan is flat, so this never returns
     *     to a provider it has already given up on
     */
    public record Llm(String provider, String fallbackProvider, String openAiBaseUrl, String openAiApiKey,
            String openAiModel, String anthropicBaseUrl, String anthropicApiKey, String anthropicModel,
            int attemptsPerProvider, Duration timeout) {

        public Llm {
            provider = blankTo(provider, "none");
            fallbackProvider = blankTo(fallbackProvider, "none");
            openAiBaseUrl = blankTo(openAiBaseUrl, "https://api.openai.com/v1");
            openAiModel = blankTo(openAiModel, "gpt-4.1-mini");
            anthropicBaseUrl = blankTo(anthropicBaseUrl, "https://api.anthropic.com");
            anthropicModel = blankTo(anthropicModel, "claude-opus-5");
            attemptsPerProvider = attemptsPerProvider <= 0 ? 2 : attemptsPerProvider;
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        }

        static Llm defaults() {
            return new Llm(null, null, null, null, null, null, null, null, 0, null);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
