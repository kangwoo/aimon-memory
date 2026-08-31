package dev.dyad.core.model;

import java.time.Instant;
import java.util.Map;

/** One row of the audit log. */
public record ConclusionEvent(
        long id,
        String workspaceName,
        String conclusionId,
        EventType event,
        Actor actor,
        String beforeContent,
        String afterContent,
        Map<String, Object> detail,
        Instant createdAt) {

    public ConclusionEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
