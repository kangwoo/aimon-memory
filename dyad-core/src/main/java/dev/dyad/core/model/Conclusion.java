package dev.dyad.core.model;

import dev.dyad.core.key.PairKey;
import java.time.Instant;
import java.util.List;

/**
 * A stored conclusion, without its embedding.
 *
 * <p>The vector stays out of this type on purpose: it is 6 KB of float that no caller downstream of
 * the store ever reads, and leaving it out keeps {@code equals} meaningful for fixture comparison.
 */
public record Conclusion(
        String id,
        PairKey pair,
        String sessionName,
        String content,
        String contentNorm,
        String contentAnalyzed,
        String contentHash,
        ConclusionLevel level,
        Double confidence,
        List<String> sourceIds,
        List<Long> messageIds,
        int timesDerived,
        Instant lastReinforcedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant deletedAt,
        SyncState syncState) {

    public Conclusion {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
