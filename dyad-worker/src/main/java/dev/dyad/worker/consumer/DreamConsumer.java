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

    /**
     * The card refresh shares this consumer's shape, and closes its dream row the same way.
     *
     * <p>It used not to touch the scheduling state at all. The row it was scheduled from stayed {@code
     * pending} forever, and {@code ReconcilerService.requeueOrphanedDreams} — which exists to rescue a
     * dream whose work unit never reached the queue — cannot tell that from a dream that has already
     * run: it found no pending queue item, concluded the unit was lost, and enqueued another. Every
     * reconciler pass, for the life of the deployment, each one a model call in {@link
     * PeerCardService#refresh}. The partial unique index meanwhile counts a pending row as in flight,
     * so the pair could never dream again and {@code POST /dreams} answered 409 permanently.
     */
    @Component
    public static class CardRefreshConsumer implements WorkUnitConsumer {

        private final PeerCardService cards;
        private final DreamRepository dreams;

        public CardRefreshConsumer(PeerCardService cards, DreamRepository dreams) {
            this.cards = cards;
            this.dreams = dreams;
        }

        @Override
        public TaskType taskType() {
            return TaskType.CARD_REFRESH;
        }

        @Override
        public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
            // One refresh for the unit, but every row that asked for it gets closed: the queue
            // serialises on the key, so two schedules for the same pair arrive as two items here.
            List<String> dreamIds =
                    items.stream()
                            .map(item -> item.payload().get("dream_id"))
                            .filter(java.util.Objects::nonNull)
                            .map(Object::toString)
                            .toList();
            dreamIds.forEach(dreams::start);
            try {
                List<String> lines = cards.refresh(key.pair());
                dreamIds.forEach(id -> dreams.complete(id, lines.size()));
            } catch (RuntimeException e) {
                // Failed, not left pending. A pending row is indistinguishable from lost work.
                dreamIds.forEach(id -> dreams.fail(id, e.getMessage()));
                throw e;
            }
        }
    }
}
