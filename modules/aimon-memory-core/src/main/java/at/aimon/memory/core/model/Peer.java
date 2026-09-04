package at.aimon.memory.core.model;

import java.time.Instant;
import java.util.Map;

/** A participant: a human, an agent, anything that can speak or be spoken about. */
public record Peer(String name, String workspaceName, Map<String, Object> metadata, Map<String, Object> configuration,
        Instant createdAt) {

    public Peer {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
}
