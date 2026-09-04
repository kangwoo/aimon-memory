package at.aimon.memory.engine.summarize;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import at.aimon.memory.core.NotFoundException;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.core.model.Session;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.core.spi.llm.LlmMessage;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.engine.derive.MessageFormatter;
import at.aimon.memory.engine.prompt.Prompts;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.text.TokenCounter;

/**
 * Two rolling summaries per session.
 *
 * <p>Short every 20 messages, long every 60. Two rather than one because they serve different
 * budgets: {@code context()} usually wants the short one and the remaining room for verbatim
 * messages, while a long history needs the detailed one to avoid losing specifics entirely.
 *
 * <p>Each regeneration is given the previous summary and produces a summary of everything so far,
 * not of the new messages alone. Chaining summaries of summaries loses information geometrically;
 * subsuming the previous one keeps a single lineage that always covers the whole session.
 */
@Service
public class SummarizerService {

    public static final int SHORT_EVERY = 20;
    public static final int LONG_EVERY = 60;

    private static final String METADATA_KEY = "summaries";

    private final LlmClient llm;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final Clock clock;

    public SummarizerService(LlmClient llm, SessionRepository sessions, MessageRepository messages, Clock clock) {
        this.llm = llm;
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Optional<Summary> find(Session session, String kind) {
        Object raw = session.internalMetadata().get(METADATA_KEY);
        if (!(raw instanceof Map<?, ?> summaries)) {
            return Optional.empty();
        }
        Object entry = summaries.get(kind);
        return entry instanceof Map<?, ?> map
                ? Optional.of(Summary.fromMap((Map<String, Object>) map))
                : Optional.empty();
    }

    /** @return the summaries that were regenerated, if any thresholds were crossed */
    public List<Summary> refresh(String workspace, String sessionName) {
        // Named, not bare — the same reason as ContextService, plus one of its own: this runs inside a
        // work unit, where a NoSuchElementException with no message is a failure the worker retries
        // five times and then quarantines, having said nothing about what was missing.
        Session session = requireSession(workspace, sessionName);
        int total = messages.countInSession(workspace, sessionName);

        List<Summary> produced = new java.util.ArrayList<>(2);
        summariseIfDue(session, total, Summary.SHORT, SHORT_EVERY, Prompts.SUMMARY_SHORT).ifPresent(produced::add);
        Session reloaded = produced.isEmpty() ? session : requireSession(workspace, sessionName);
        summariseIfDue(reloaded, total, Summary.LONG, LONG_EVERY, Prompts.SUMMARY_LONG).ifPresent(produced::add);
        return List.copyOf(produced);
    }

    private Session requireSession(String workspace, String sessionName) {
        return sessions.find(workspace, sessionName).orElseThrow(() -> new NotFoundException("session", sessionName));
    }

    private Optional<Summary> summariseIfDue(Session session, int totalMessages, String kind, int every,
            String prompt) {
        Optional<Summary> existing = find(session, kind);
        long covered = existing.map(Summary::coversThroughSeq).orElse(0L);
        if (totalMessages - covered < every) {
            return Optional.empty();
        }

        List<Message> pending = messages.inSession(session.workspaceName(), session.name(), covered + 1, every * 4);
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder input = new StringBuilder();
        existing.ifPresent(summary -> input.append("Summary so far:\n").append(summary.text()).append("\n\n"));
        input.append("New messages:\n").append(MessageFormatter.format(pending));

        String text = llm.structured(
                new LlmRequest(null, prompt, List.of(LlmMessage.user(input.toString())), 0.0, null,
                        at.aimon.memory.core.spi.llm.ResponseFormat.strict("summary",
                                "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"summary\"],\"additionalProperties\":false}")),
                SummaryPayload.class).value().summary();

        long coversThrough = pending.get(pending.size() - 1).seqInSession();
        Summary summary = new Summary(kind, text, coversThrough, TokenCounter.count(text), clock.instant());
        store(session, summary);
        return Optional.of(summary);
    }

    private void store(Session session, Summary summary) {
        Map<String, Object> internal = new LinkedHashMap<>(session.internalMetadata());
        Object raw = internal.get(METADATA_KEY);
        Map<String, Object> summaries = raw instanceof Map<?, ?> map
                ? new LinkedHashMap<>(castMap(map))
                : new LinkedHashMap<>();
        summaries.put(summary.kind(), summary.toMap());
        internal.put(METADATA_KEY, summaries);
        sessions.updateInternalMetadata(session.workspaceName(), session.name(), internal);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** Wrapper so the summariser can use the same structured-output path as everything else. */
    public record SummaryPayload(String summary) {
    }
}
