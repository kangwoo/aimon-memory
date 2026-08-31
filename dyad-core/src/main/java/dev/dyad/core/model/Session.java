package dev.dyad.core.model;

import java.time.Instant;
import java.util.Map;

/** A conversation. Messages hang off it; so do summaries, in {@code internalMetadata}. */
public record Session(
        String name,
        String workspaceName,
        boolean isActive,
        Map<String, Object> metadata,
        Map<String, Object> configuration,
        Map<String, Object> internalMetadata,
        Instant createdAt) {

    public Session {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        internalMetadata = internalMetadata == null ? Map.of() : Map.copyOf(internalMetadata);
    }
}
