package dev.dyad.worker.consumer;

import dev.dyad.core.key.TaskType;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.memory.dream.DreamerService;
import dev.dyad.memory.dream.PeerCardService;
import dev.dyad.store.repo.DreamRepository;
import dev.dyad.store.repo.QueueRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DreamConsumer implements WorkUnitConsumer {

    private final DreamerService dreamer;
    private final DreamRepository dreams;

    public DreamConsumer(DreamerService dreamer, DreamRepository dreams) {
        this.dreamer = dreamer;
        this.dreams = dreams;
    }

    @Override
    public TaskType taskType() {
        return TaskType.DREAM;
    }

    @Override
    public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
        for (QueueRepository.QueueItem item : items) {
            Object id = item.payload().get("dream_id");
            if (id == null) {
                continue;
            }
            dreams.find(id.toString()).ifPresent(dreamer::run);
        }
    }

    /** The card refresh shares this consumer's shape but never touches the dream scheduling state. */
    @Component
    public static class CardRefreshConsumer implements WorkUnitConsumer {

        private final PeerCardService cards;

        public CardRefreshConsumer(PeerCardService cards) {
            this.cards = cards;
        }

        @Override
        public TaskType taskType() {
            return TaskType.CARD_REFRESH;
        }

        @Override
        public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
            cards.refresh(key.pair());
        }
    }
}
