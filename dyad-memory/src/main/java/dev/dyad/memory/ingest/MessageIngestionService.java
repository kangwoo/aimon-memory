package dev.dyad.memory.ingest;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.SessionPeer;
import dev.dyad.memory.fanout.ObserverResolver;
import dev.dyad.store.WorkspaceSettingsService;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.SessionPeerRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.text.TokenCounter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write path in front of HTTP: store the messages, queue the work, return.
 *
 * <p>No model is called here, ever. Blocking an HTTP request on an LLM is what turns a memory system
 * into a latency problem for everything that writes to it. The one exception is opt-in and per
 * request, not a global switch — a global one costs everybody the batching win to serve the few
 * callers that need read-your-writes.
 */
@Service
public class MessageIngestionService {

    /** Messages per request. Bounded so one call cannot allocate an unbounded sequence block. */
    public static final int MAX_BATCH = 100;

    private final WorkspaceRepository workspaces;
    private final PeerRepository peers;
    private final SessionRepository sessions;
    private final SessionPeerRepository sessionPeers;
    private final MessageRepository messages;
    private final CollectionRepository collections;
    private final QueueRepository queue;
    private final WorkspaceSettingsService settings;

    public MessageIngestionService(
            WorkspaceRepository workspaces,
            PeerRepository peers,
            SessionRepository sessions,
            SessionPeerRepository sessionPeers,
            MessageRepository messages,
            CollectionRepository collections,
            QueueRepository queue,
            WorkspaceSettingsService settings) {
        this.workspaces = workspaces;
        this.peers = peers;
        this.sessions = sessions;
        this.sessionPeers = sessionPeers;
        this.messages = messages;
        this.collections = collections;
        this.queue = queue;
        this.settings = settings;
    }

    public record IncomingMessage(String peerName, String content, Map<String, Object> metadata) {}

    public record IngestResult(List<Message> messages, List<WorkUnitKey> queued) {}

    @Transactional
    public IngestResult ingest(String workspace, String sessionName, List<IncomingMessage> incoming) {
        if (incoming.isEmpty()) {
            return new IngestResult(List.of(), List.of());
        }
        if (incoming.size() > MAX_BATCH) {
            throw new dev.dyad.core.DyadException(
                    "batch_too_large", "at most " + MAX_BATCH + " messages per request");
        }

        workspaces.getOrCreate(workspace, Map.of(), Map.of());
        sessions.getOrCreate(workspace, sessionName, Map.of(), Map.of());
        for (IncomingMessage message : incoming) {
            peers.getOrCreate(workspace, message.peerName(), Map.of(), Map.of());
            // Attendance only. Passing nulls for the observe flags here overwrote whatever the session
            // was configured with, so an explicit observe_others: false lasted until the peer's next
            // message and then quietly reverted to the workspace default.
            sessionPeers.join(workspace, sessionName, message.peerName());
        }

        List<MessageRepository.NewMessage> rows = new ArrayList<>(incoming.size());
        for (IncomingMessage message : incoming) {
            rows.add(
                    new MessageRepository.NewMessage(
                            message.peerName(),
                            message.content(),
                            TokenCounter.count(message.content()),
                            message.metadata() == null ? Map.of() : message.metadata()));
        }
        long start = sessions.nextSequence(workspace, sessionName, rows.size());
        List<Message> saved = messages.insertBatch(workspace, sessionName, start, rows);

        List<SessionPeer> members = sessionPeers.members(workspace, sessionName);
        var workspaceSettings = settings.forWorkspace(workspace);

        Set<WorkUnitKey> queued = new LinkedHashSet<>();
        int batchTokens = 0;
        for (Message message : saved) {
            batchTokens += message.tokenCount();
            for (PairKey pair : ObserverResolver.observersOf(message, members, workspaceSettings)) {
                collections.getOrCreate(pair);
                WorkUnitKey key = WorkUnitKey.representation(workspace, sessionName, pair);
                queue.enqueue(key, Map.of("message_id", message.id()), message.tokenCount());
                queued.add(key);
            }
        }

        // Nothing used to enqueue this, so SummaryConsumer never received work: no session ever got a
        // rolling summary, context() always returned an empty one, and the 40/60 budget split it
        // documents never engaged. One trigger per request rather than per message — the summariser
        // decides from the message count whether a threshold was actually crossed, so a burst of
        // triggers still produces one regeneration.
        queue.enqueue(WorkUnitKey.summary(workspace, sessionName), Map.of(), batchTokens);

        // Deliberately not in `queued`: that list is what ?wait=derive blocks on, and a caller asking
        // to see its own conclusions should not also wait on a summary regeneration it did not ask for.
        return new IngestResult(saved, List.copyOf(queued));
    }

    /** True once nothing is left pending on any of these work units. */
    public boolean isDrained(List<WorkUnitKey> keys) {
        for (WorkUnitKey key : keys) {
            if (!queue.pending(key.encode(), 1).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
