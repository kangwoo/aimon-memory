package dev.dyad.core.spi.llm;

/**
 * A parsed structured-output response.
 *
 * @param value the deserialised object
 * @param rawJson exactly what the provider returned, kept for fixtures and debugging
 * @param model the model that actually answered — may differ from the request after a fallback
 */
public record StructuredResult<T>(T value, String rawJson, String model, LlmUsage usage) {}
