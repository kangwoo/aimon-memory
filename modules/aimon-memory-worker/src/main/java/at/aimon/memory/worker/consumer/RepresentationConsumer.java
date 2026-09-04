package at.aimon.memory.worker.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.engine.derive.DeriverService;
import at.aimon.memory.engine.dream.DreamerService;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.QueueRepository;

/**
 * Turns a batch of queued messages into conclusions.
 *
 * <p>One model call for the whole batch — the batch being one pair's share of a session's messages,
 * since the queue keys on the pair. That serialisation is what keeps two concurrent batches for the
 * same pair from racing in dedup and producing near-duplicates for stage 3 to clean up afterwards.
 * Other pairs observing the same messages have their own work units and their own calls; ADR 0006
 * records why.
 */
@Component
public class RepresentationConsumer implements WorkUnitConsumer {

    private static final Logger log = LoggerFactory.getLogger(RepresentationConsumer.class);

    private final MessageRepository messages;
    private final DeriverService deriver;
    private final DreamerService dreamer;
    private final QueueRepository queue;

    public RepresentationConsumer(MessageRepository messages, DeriverService deriver, DreamerService dreamer,
            QueueRepository queue) {
        this.messages = messages;
        this.deriver = deriver;
        this.dreamer = dreamer;
        this.queue = queue;
    }

    @Override
    public TaskType taskType() {
        return TaskType.REPRESENTATION;
    }

    @Override
    public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
        List<Long> messageIds = new ArrayList<>(items.size());
        for (QueueRepository.QueueItem item : items) {
            Object id = item.payload().get("message_id");
            if (id instanceof Number number) {
                messageIds.add(number.longValue());
            }
        }
        List<Message> batch = messages.byIds(key.workspaceName(), messageIds);
        if (batch.isEmpty()) {
            return;
        }

        var result = deriver.deriveAndWrite(key.pair(), key.sessionName(), batch);
        log.debug("{}: {} inserted, {} reinforced, {} replaced", key, result.inserted(), result.reinforced(),
                result.replaced());

        // Checked after every batch rather than on a timer: the thresholds are about how much new
        // material exists, and this is the only place that number changes.
        //
        // Scheduling writes a pending row and nothing more, so the work unit has to be enqueued here.
        // Without it the row sat forever, and — because the partial unique index counts a pending
        // dream as in flight — it also refused every later dream for the pair, including the manual
        // endpoint, which answered 409 indefinitely for a dream that was never going to start.
        dreamer.scheduleIfDue(key.pair())
                .ifPresent(dream -> queue.enqueue(WorkUnitKey.dream(key.pair()), Map.of("dream_id", dream.id()), 0));
    }
}
