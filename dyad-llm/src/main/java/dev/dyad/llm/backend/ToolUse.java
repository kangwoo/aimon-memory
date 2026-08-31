package dev.dyad.llm.backend;

/** A model's request to call a tool. {@code id} is the provider's correlation handle. */
public record ToolUse(String id, String name, String argumentsJson) {}
