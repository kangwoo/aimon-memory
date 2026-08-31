package dev.dyad.worker.consumer;

import dev.dyad.core.key.TaskType;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.EventType;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.QueueRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Cascades a delete: soft-delete the conclusion, drop its entity edges, collect orphaned nodes.
 *
 * <p>Queued rather than done inline because the orphan sweep is a scan and the caller asked to delete
 * one row, not to wait for a table.
 */
@Component
public class DeletionConsumer implements WorkUnitConsumer {

    private final ConclusionRepository conclusions;
    private final EntityPipeline entities;

    public DeletionConsumer(ConclusionRepository conclusions, EntityPipeline entities) {
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
            conclusions.softDelete(
                    key.workspaceName(), conclusionId, Actor.API, Map.of("cascade", true), EventType.DELETE);
            entities.unlink(key.workspaceName(), conclusionId);
        }
    }
}
