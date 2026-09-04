package at.aimon.memory.llm.backend;

/** What a handler produced, addressed back to the {@link ToolUse} that asked for it. */
public record ToolResult(String toolUseId, String name, String content, boolean isError) {
}
