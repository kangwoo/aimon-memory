package at.aimon.memory.worker.consumer;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.store.repo.QueueRepository;

/**
 * Cascades a delete: soft-delete the conclusion, drop its entity edges, collect orphaned nodes.
 *
 * <p>Queued rather than done inline because the orphan sweep is a scan and the caller asked to delete
 * one row, not to wait for a table.
 */
@Component
public class DeletionConsumer implements WorkUnitConsumer {

    private final ConclusionStore conclusions;
    private final EntityPipeline entities;

    public DeletionConsumer(ConclusionStore conclusions, EntityPipeline entities) {
        this.conclusions = conclusions;
        this.entities = entities;
    }

    @Override
    public TaskType taskType() {
        return TaskType.DELETION;
    }

    @Override
    public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
        for (QueueRepository.QueueItem item : items) {
            Object id = item.payload().get("conclusion_id");
            if (id == null) {
                continue;
            }
            String conclusionId = id.toString();
            conclusions.softDelete(key.workspaceName(), conclusionId, Actor.API, Map.of("cascade", true),
                    EventType.DELETE);
            entities.unlink(key.workspaceName(), conclusionId);
        }
    }
}
