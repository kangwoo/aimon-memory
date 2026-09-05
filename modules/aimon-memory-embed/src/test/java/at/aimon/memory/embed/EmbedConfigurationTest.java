package at.aimon.memory.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;

/**
 * A provider name this build does not implement stops the process.
 *
 * <p>The branch these tests replace was {@code if ("openai".equals(provider)) … else hashing}, and
 * the failure it allowed had no symptom: {@code AIMON_MEMORY_EMBED_PROVIDER=openal} started a
 * deployment that wrote lexical vectors into a semantic index, answered every recall, and was only
 * wrong in the ranking. A missing API key already stopped startup here, so the two halves of the same
 * mistake were being treated differently.
 */
class EmbedConfigurationTest {

    private final EmbedConfiguration configuration = new EmbedConfiguration();

    private static EmbedProperties provider(String name, String apiKey) {
        return new EmbedProperties(name, null, apiKey, null, 0, 0, 0, 0, Duration.ofSeconds(30));
    }

    @Test
    void anUnknownProviderNameFailsRatherThanFallingBackToHashing() {
        assertThatThrownBy(() -> configuration.embedder(provider("openal", "sk-test")))
                .isInstanceOf(MemoryException.class).hasMessageContaining("openal")
                .hasMessageContaining("openai, hashing");
    }

    /** Case is not the mistake being guarded against, and never was. */
    @Test
    void providerNamesAreCaseInsensitive() {
        assertThat(configuration.embedder(provider("OpenAI", "sk-test"))).isInstanceOf(OpenAiEmbedder.class);
        assertThat(configuration.embedder(provider("HASHING", null))).isInstanceOf(HashingEmbedder.class);
    }

    @Test
    void hashingIsTheDefaultSoTheSystemStartsWithoutCredentials() {
        assertThat(configuration.embedder(provider(null, null))).isInstanceOf(HashingEmbedder.class);
    }

    @Test
    void openAiWithoutAnApiKeyStillFailsAtStartup() {
        assertThatThrownBy(() -> configuration.embedder(provider("openai", null))).isInstanceOf(MemoryException.class)
                .hasMessageContaining("aimon.memory.embed.api-key");
    }
}
