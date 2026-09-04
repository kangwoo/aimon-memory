package at.aimon.memory.llm.backend;

import java.util.List;

import at.aimon.memory.core.spi.llm.ResponseFormat;

/**
 * A single provider round trip, fully resolved.
 *
 * <p>"Fully resolved" is load-bearing: the model name must already be concrete before a call reaches
 * the recorder, or a fixture recorded under the default model would replay for an explicit one.
 */
public record ChatCall(String model, String system, List<ChatTurn> turns, List<ToolSpec> tools,
        ResponseFormat responseFormat, Double temperature, Integer maxTokens) {

    public ChatCall {
        turns = List.copyOf(turns);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public ChatCall withModel(String newModel) {
        return new ChatCall(newModel, system, turns, tools, responseFormat, temperature, maxTokens);
    }
}
