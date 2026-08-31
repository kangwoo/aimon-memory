package dev.dyad.llm.backend;

import java.util.List;

/**
 * One turn of provider-level conversation.
 *
 * <p>Richer than the SPI's {@code LlmMessage} because a tool loop needs to send back what the model
 * asked for and what came of it. Providers disagree on how to encode that; this is the shape both
 * backends translate from.
 */
public sealed interface ChatTurn {

    record UserText(String text) implements ChatTurn {}

    record AssistantText(String text) implements ChatTurn {}

    /** The model's turn when it asked for tools. {@code text} is any prose it emitted alongside. */
    record AssistantToolUse(String text, List<ToolUse> uses) implements ChatTurn {
        public AssistantToolUse {
            uses = List.copyOf(uses);
        }
    }

    record ToolResults(List<ToolResult> results) implements ChatTurn {
        public ToolResults {
            results = List.copyOf(results);
        }
    }
}
