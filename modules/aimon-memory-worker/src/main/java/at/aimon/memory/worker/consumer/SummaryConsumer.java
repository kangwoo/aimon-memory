package at.aimon.memory.worker.consumer;

import java.util.List;

import org.springframework.stereotype.Component;

import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.engine.summarize.SummarizerService;
import at.aimon.memory.store.repo.QueueRepository;

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
