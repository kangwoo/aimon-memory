package at.aimon.memory.core.spi.llm;

import java.util.List;

/**
 * One call to a model, provider-neutral.
 *
 * <p>{@code model} may be null, in which case the client fills in its configured default. The
 * replay harness hashes the resolved form, so a null here is not the same key as an explicit model.
 */
public record LlmRequest(String model, String system, List<LlmMessage> messages, Double temperature, Integer maxTokens,
        ResponseFormat responseFormat) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static LlmRequest of(String system, String userText) {
        return new LlmRequest(null, system, List.of(LlmMessage.user(userText)), null, null, null);
    }

    public LlmRequest withModel(String newModel) {
        return new LlmRequest(newModel, system, messages, temperature, maxTokens, responseFormat);
    }

    public LlmRequest withResponseFormat(ResponseFormat format) {
        return new LlmRequest(model, system, messages, temperature, maxTokens, format);
    }

    public LlmRequest withMessages(List<LlmMessage> newMessages) {
        return new LlmRequest(model, system, newMessages, temperature, maxTokens, responseFormat);
    }

    public LlmRequest withMaxTokens(Integer newMaxTokens) {
        return new LlmRequest(model, system, messages, temperature, newMaxTokens, responseFormat);
    }
}
