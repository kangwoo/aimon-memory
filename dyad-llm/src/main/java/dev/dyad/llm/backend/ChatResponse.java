package dev.dyad.llm.backend;

import dev.dyad.core.spi.llm.LlmUsage;
import java.util.List;

/**
 * What came back.
 *
 * @param model the model that actually answered — after a fallback this differs from the request
 * @param rawJson the provider payload, kept so a fixture is debuggable without re-running anything
 */
public record ChatResponse(
        String text, List<ToolUse> toolUses, String model, LlmUsage usage, String rawJson) {

    public ChatResponse {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    public boolean wantsTools() {
        return !toolUses.isEmpty();
    }
}
