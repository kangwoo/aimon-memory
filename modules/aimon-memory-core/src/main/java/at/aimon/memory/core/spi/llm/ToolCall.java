package at.aimon.memory.core.spi.llm;

/** One tool invocation inside a loop, with whatever the handler produced. */
public record ToolCall(String name, String argumentsJson, String output, boolean failed) {
}
