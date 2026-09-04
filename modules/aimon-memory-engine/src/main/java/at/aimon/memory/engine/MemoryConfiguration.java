package at.aimon.memory.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.embed.EmbeddingProperties;
import at.aimon.memory.embed.OpenAiEmbedder;
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
import at.aimon.memory.store.StoreConfiguration;
import at.aimon.memory.text.AnalyzerRegistry;

/** Shared wiring: both the API and the worker import this and then add their own layer. */
@Configuration
@Import(StoreConfiguration.class)
@ComponentScan(basePackages = {"at.aimon.memory.engine", "at.aimon.memory.recall"})
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AnalyzerRegistry analyzerRegistry(MemoryProperties properties) {
        String dictionary = properties.text().koreanUserDictionary();
        return new AnalyzerRegistry(dictionary == null || dictionary.isBlank() ? null : Path.of(dictionary));
    }

    @Bean
    public Embedder embedder(MemoryProperties properties) {
        MemoryProperties.Embed embed = properties.embed();
        if ("openai".equalsIgnoreCase(embed.provider())) {
            return new OpenAiEmbedder(new EmbeddingProperties(embed.baseUrl(),
                    require(embed.apiKey(), "aimon.memory.embed.api-key"), embed.model(), embed.dimensions(),
                    embed.maxBatchSize(), embed.maxInputTokens(), embed.maxAttempts(), embed.timeout()));
        }
        return new HashingEmbedder(embed.dimensions());
    }

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

    private static ChatBackend backendFor(String name, MemoryProperties properties) {
        MemoryProperties.Llm llm = properties.llm();
        return switch (name == null ? "none" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "openai" -> new OpenAiChatBackend(llm.openAiBaseUrl(),
                    require(llm.openAiApiKey(), "aimon.memory.llm.open-ai-api-key"), llm.openAiModel(), llm.timeout());
            case "anthropic" -> new AnthropicChatBackend(llm.anthropicBaseUrl(),
                    require(llm.anthropicApiKey(), "aimon.memory.llm.anthropic-api-key"), llm.anthropicModel(),
                    llm.timeout());
            default -> null;
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
