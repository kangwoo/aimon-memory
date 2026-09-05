package at.aimon.memory.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.llm.DefaultLlmClient;
import at.aimon.memory.llm.LlmMode;
import at.aimon.memory.llm.backend.AnthropicChatBackend;
import at.aimon.memory.llm.backend.AttemptPlan;
import at.aimon.memory.llm.backend.ChatBackend;
import at.aimon.memory.llm.backend.ChatCall;
import at.aimon.memory.llm.backend.ChatResponse;
import at.aimon.memory.llm.backend.FallbackChatBackend;
import at.aimon.memory.llm.backend.OpenAiChatBackend;
import at.aimon.memory.llm.replay.RecordingChatBackend;
import at.aimon.memory.recall.RecallConfiguration;

/**
 * Shared wiring: both the API and the worker import this and then add their own layer.
 *
 * <p>What this class no longer does is the more informative half. It used to component-scan
 * {@code at.aimon.memory.recall} and define the {@code Clock}, {@code AnalyzerRegistry} and
 * {@code Embedder} beans that recall and store need — which made two published coordinates
 * un-assemblable without this one, in the opposite direction to the layering the build enforces.
 * Each of those now belongs to the lowest module that needs it, and this class reaches them by
 * importing {@link RecallConfiguration}, which brings {@code StoreConfiguration} and
 * {@code EmbedConfiguration} with it. What is left here is what only engine can own: the chat
 * provider chain and its record/replay seam.
 */
@Configuration
@Import(RecallConfiguration.class)
@ComponentScan(basePackages = "at.aimon.memory.engine")
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {

    /**
     * The LLM client, wrapped for record/replay.
     *
     * <p>Recording wraps each provider individually and the fallback chain composes the wrapped ones.
     * The other order would key every fixture under the primary's model even when the secondary
     * answered, and the replay would then serve one provider's response in the other's shape.
     */
    @Bean
    public LlmClient llmClient(MemoryProperties properties) {
        List<ChatBackend> backends = new ArrayList<>();
        Stream.of(properties.llm().provider(), properties.llm().fallbackProvider())
                .map(name -> backendFor(name, properties)).filter(java.util.Objects::nonNull)
                .map(RecordingChatBackend::around).forEach(backends::add);

        if (backends.isEmpty()) {
            // Replay-only deployments are legitimate — that is how CI runs — so an unconfigured
            // provider is not fatal until something actually asks for a completion.
            return new DefaultLlmClient(new UnconfiguredBackend());
        }
        ChatBackend backend = backends.size() == 1
                ? backends.get(0)
                : new FallbackChatBackend(AttemptPlan.of(backends, properties.llm().attemptsPerProvider()));
        return new DefaultLlmClient(backend);
    }

    /**
     * A backend for one configured provider name, or {@code null} for the two spellings of "no
     * provider here".
     *
     * <p>Anything else fails at startup. It used to return {@code null} for every unrecognised name,
     * which turned {@code AIMON_MEMORY_LLM_PROVIDER=openal} into a deployment that starts, reports
     * healthy, and answers every derivation and dialectic request with {@code llm_not_configured} —
     * a message that names the setting the operator did set. A missing API key already stops the
     * process, and an unusable provider name is the same mistake at the same boundary; the reason
     * this one was quiet is that {@code none} and "not a provider" shared a branch. They no longer
     * do: {@code none} is a decision and is honoured, a typo is not a decision.
     */
    private static ChatBackend backendFor(String name, MemoryProperties properties) {
        MemoryProperties.Llm llm = properties.llm();
        return switch (name == null ? "none" : name.toLowerCase(Locale.ROOT)) {
            case "openai" -> new OpenAiChatBackend(llm.openAiBaseUrl(),
                    require(llm.openAiApiKey(), "aimon.memory.llm.open-ai-api-key"), llm.openAiModel(), llm.timeout());
            case "anthropic" -> new AnthropicChatBackend(llm.anthropicBaseUrl(),
                    require(llm.anthropicApiKey(), "aimon.memory.llm.anthropic-api-key"), llm.anthropicModel(),
                    llm.timeout());
            case "none", "" -> null;
            default -> throw new MemoryException("unknown_llm_provider",
                    "aimon.memory.llm.provider (or .fallback-provider) is '" + name + "', which this build does not "
                            + "implement. Known providers: openai, anthropic, none. Startup fails rather than "
                            + "treating it as 'none', because that produced a deployment which started healthy and "
                            + "then answered every model call with llm_not_configured.");
        };
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new MemoryException("missing_config", property + " is required for this provider");
        }
        return value;
    }

    /** Fails only when called, and says exactly which setting is missing. */
    private static final class UnconfiguredBackend implements ChatBackend {

        @Override
        public ChatResponse chat(ChatCall call) {
            throw new MemoryException("llm_not_configured",
                    "No LLM provider is configured. Set aimon.memory.llm.provider, or run with"
                            + " AIMON_MEMORY_LLM_MODE=replay against recorded fixtures.");
        }

        @Override
        public Stream<String> stream(ChatCall call) {
            return chat(call) == null ? Stream.of() : Stream.of();
        }

        @Override
        public String defaultModel() {
            return "unconfigured";
        }

        @Override
        public String providerName() {
            return "none";
        }
    }

    @Bean
    public LlmMode llmMode() {
        return LlmMode.fromEnvironment();
    }
}
