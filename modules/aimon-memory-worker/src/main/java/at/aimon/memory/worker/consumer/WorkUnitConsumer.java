package at.aimon.memory.worker.consumer;

import java.util.List;

import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.store.repo.QueueRepository;

/** Handles one kind of work unit. One implementation per {@link at.aimon.memory.core.key.TaskType}. */
public interface WorkUnitConsumer {

    at.aimon.memory.core.key.TaskType taskType();

    /**
     * Process a claimed batch.
     *
     * <p>The claim is already held and the items are already selected. Throwing leaves the items
     * unprocessed with their attempt count raised; returning normally consumes them.
     */
    void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items);
}
