package dev.dyad.worker.consumer;

import dev.dyad.core.key.TaskType;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.core.model.Message;
import dev.dyad.memory.derive.DeriverService;
import dev.dyad.memory.dream.DreamerService;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.QueueRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a batch of queued messages into conclusions.
 *
 * <p>One model call for the whole batch, which is the reason the queue serialises on the pair: two
 * concurrent batches for the same pair would race in dedup and produce near-duplicates that stage 3
 * then has to clean up after the fact.
 */
@Component
public class RepresentationConsumer implements WorkUnitConsumer {

    private static final Logger log = LoggerFactory.getLogger(RepresentationConsumer.class);

    private final MessageRepository messages;
    private final DeriverService deriver;
    private final DreamerService dreamer;

    public RepresentationConsumer(
            MessageRepository messages, DeriverService deriver, DreamerService dreamer) {
        this.messages = messages;
        this.deriver = deriver;
        this.dreamer = dreamer;
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
        log.debug(
                "{}: {} inserted, {} reinforced, {} replaced",
                key,
                result.inserted(),
                result.reinforced(),
                result.replaced());

        // Checked after every batch rather than on a timer: the thresholds are about how much new
        // material exists, and this is the only place that number changes.
        dreamer.scheduleIfDue(key.pair());
    }
}
