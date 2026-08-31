package dev.dyad.core.spi.llm;

/**
 * A tool the model may call.
 *
 * @param parametersSchema JSON Schema for the arguments object
 * @param handler executes the call; receives the raw arguments JSON, returns the tool output
 */
public record ToolDef(String name, String description, String parametersSchema, ToolHandler handler) {

    @FunctionalInterface
    public interface ToolHandler {
        String invoke(String argumentsJson);
    }
}
