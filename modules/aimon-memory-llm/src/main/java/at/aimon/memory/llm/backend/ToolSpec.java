package at.aimon.memory.llm.backend;

/** A tool as the provider sees it — no handler, just the declaration. */
public record ToolSpec(String name, String description, String parametersSchema) {
}
