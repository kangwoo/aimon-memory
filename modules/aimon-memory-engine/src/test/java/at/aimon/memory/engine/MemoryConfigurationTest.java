package at.aimon.memory.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;

/**
 * A chat provider name this build does not implement stops the process.
 *
 * <p>{@code backendFor} used to return {@code null} for every name it did not recognise, and the
 * caller filtered nulls out — so {@code none} and {@code openal} were the same answer. The second one
 * produced a deployment that started, reported healthy, and failed every derivation, summary and
 * dialectic call with {@code llm_not_configured}, naming the setting the operator had in fact set.
 *
 * <p>The distinction the tests below pin is that {@code none} is a decision and a typo is not.
 */
class MemoryConfigurationTest {

    private final MemoryConfiguration configuration = new MemoryConfiguration();

    private static MemoryProperties llm(String provider, String fallback) {
        return new MemoryProperties(
                new MemoryProperties.Llm(provider, fallback, null, "sk-test", null, null, "sk-test", null, 0, null));
    }

    @Test
    void anUnknownProviderNameFailsRatherThanDisablingTheModelQuietly() {
        assertThatThrownBy(() -> configuration.llmClient(llm("openal", "none"))).isInstanceOf(MemoryException.class)
                .hasMessageContaining("openal").hasMessageContaining("openai, anthropic, none");
    }

    /** The fallback slot is configured the same way and was equally quiet about a typo. */
    @Test
    void anUnknownFallbackProviderNameFailsToo() {
        assertThatThrownBy(() -> configuration.llmClient(llm("openai", "anthropik")))
                .isInstanceOf(MemoryException.class).hasMessageContaining("anthropik");
    }

    @Test
    void noneIsHonouredBecauseReplayOnlyDeploymentsAreLegitimate() {
        assertThatCode(() -> configuration.llmClient(llm("none", "none"))).doesNotThrowAnyException();
        assertThat(configuration.llmClient(llm(null, null))).isNotNull();
    }

    @Test
    void aKnownProviderStillBuildsItsBackend() {
        assertThat(configuration.llmClient(llm("openai", "anthropic"))).isNotNull();
    }

    /** A key that is missing was already fatal, and stays fatal for the same reason. */
    @Test
    void aKnownProviderWithoutItsApiKeyStillFailsAtStartup() {
        MemoryProperties missingKey = new MemoryProperties(
                new MemoryProperties.Llm("openai", "none", null, null, null, null, null, null, 0, null));
        assertThatThrownBy(() -> configuration.llmClient(missingKey)).isInstanceOf(MemoryException.class)
                .hasMessageContaining("aimon.memory.llm.open-ai-api-key");
    }
}
