package at.aimon.memory.core.model;

import java.time.Instant;
import java.util.Map;

/**
 * One utterance.
 *
 * @param seqInSession monotonic per session; the ordering the summariser and {@code context()} walk
 * @param tokenCount counted at write time so batching never has to re-tokenise
 */
public record Message(long id, String workspaceName, String sessionName, String peerName, String content,
        long seqInSession, int tokenCount, Map<String, Object> metadata, Instant createdAt) {

    public Message {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
