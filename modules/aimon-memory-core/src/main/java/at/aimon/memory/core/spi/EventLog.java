package at.aimon.memory.core.spi;

import java.util.List;
import java.util.Map;

import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionEvent;
import at.aimon.memory.core.model.EventType;

/**
 * The audit sink.
 *
 * <p>An SPI rather than a repository detail because the dreamer edits conclusions on its own
 * initiative. The moment a process changes memory without a human in the loop, "what changed and
 * who did it" stops being a nice-to-have.
 */
public interface EventLog {

    void append(String workspaceName, String conclusionId, EventType event, Actor actor, String beforeContent,
            String afterContent, Map<String, Object> detail);

    List<ConclusionEvent> history(String workspaceName, String conclusionId, int limit);
}
