package at.aimon.memory.core.model;

import java.time.Instant;
import java.util.Map;

/** Top of the hierarchy and the tenancy boundary. Every other row carries its name. */
public record Workspace(String name, Map<String, Object> metadata, Map<String, Object> configuration,
        Instant createdAt) {

    public Workspace {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
}
