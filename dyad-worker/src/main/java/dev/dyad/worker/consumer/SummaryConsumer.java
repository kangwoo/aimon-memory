package dev.dyad.worker.consumer;

import dev.dyad.core.key.TaskType;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.memory.summarize.SummarizerService;
import dev.dyad.store.repo.QueueRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SummaryConsumer implements WorkUnitConsumer {

    private final SummarizerService summarizer;

    public SummaryConsumer(SummarizerService summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public TaskType taskType() {
        return TaskType.SUMMARY;
    }

    @Override
    public void consume(WorkUnitKey key, List<QueueRepository.QueueItem> items) {
        // The queued items are only a trigger; the summariser decides from the message count whether
        // a threshold was actually crossed, so a burst of triggers still produces one regeneration.
        summarizer.refresh(key.workspaceName(), key.sessionName());
    }
}
