package dev.dyad.worker.consumer;

import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.store.repo.QueueRepository;
import java.util.List;

/** Handles one kind of work unit. One implementation per {@link dev.dyad.core.key.TaskType}. */
public interface WorkUnitConsumer {

    dev.dyad.core.key.TaskType taskType();

    /**
     * Process a claimed batch.
     *
     * <p>The claim is already held and the items are already selected. Throwing leaves the items
     * unprocessed with their attempt count raised; returning normally consumes them.
     */
    void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items);
}
