package dev.dyad.core.spi.llm;

/**
 * A JSON Schema the model must answer in.
 *
 * @param name schema name the provider echoes back
 * @param jsonSchema the schema itself, already serialised
 * @param strict ask the provider to reject any deviation rather than best-effort
 */
public record ResponseFormat(String name, String jsonSchema, boolean strict) {

    public static ResponseFormat strict(String name, String jsonSchema) {
        return new ResponseFormat(name, jsonSchema, true);
    }
}
