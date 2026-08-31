package dev.dyad.memory.summarize;

import java.time.Instant;
import java.util.Map;

/**
 * A stored summary.
 *
 * @param coversThroughSeq the last message sequence this summary accounts for; {@code context()}
 *     starts its message window immediately after it, which is what stops the two from overlapping
 */
public record Summary(String kind, String text, long coversThroughSeq, int tokenCount, Instant createdAt) {

    public static final String SHORT = "short";
    public static final String LONG = "long";

    public Map<String, Object> toMap() {
        return Map.of(
                "kind", kind,
                "text", text,
                "covers_through_seq", coversThroughSeq,
                "token_count", tokenCount,
                "created_at", createdAt.toString());
    }

    public static Summary fromMap(Map<String, Object> map) {
        return new Summary(
                String.valueOf(map.get("kind")),
                String.valueOf(map.get("text")),
                ((Number) map.getOrDefault("covers_through_seq", 0)).longValue(),
                ((Number) map.getOrDefault("token_count", 0)).intValue(),
                Instant.parse(String.valueOf(map.getOrDefault("created_at", Instant.EPOCH.toString()))));
    }
}
