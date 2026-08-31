package dev.dyad.memory.context;

import dev.dyad.core.NotFoundException;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.Session;
import dev.dyad.memory.summarize.Summary;
import dev.dyad.memory.summarize.SummarizerService;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.SessionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Tier 0: a token-budgeted view of a session. No model calls, no vector search.
 *
 * <p>The budget splits 40/60 between summary and verbatim messages. Recent messages get the larger
 * share because they are what the next turn is actually about; the summary is there so the model
 * knows what came before, not so it can reconstruct it.
 *
 * <p>Messages are taken newest-first and then reversed, so a tight budget drops the oldest rather
 * than truncating the most recent turn — which is the one thing that must always survive.
 *
 * <p>Entirely deterministic, which is what lets the allocation be pinned by a fixture.
 */
@Service
public class ContextService {

    private static final double SUMMARY_SHARE = 0.40;

    /**
     * Upper bound on how many messages could conceivably fit the budget.
     *
     * <p>Derived rather than a constant: the shortest useful message is on the order of a couple of
     * tokens, so this cannot exclude anything the budget would have accepted, and it keeps a session
     * with a hundred thousand messages from being read into memory to select the last twenty.
     */
    private static final int MIN_TOKENS_PER_MESSAGE = 2;

    private static final int MAX_WINDOW = 5_000;

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final SummarizerService summarizer;

    public ContextService(
            SessionRepository sessions, MessageRepository messages, SummarizerService summarizer) {
        this.sessions = sessions;
        this.messages = messages;
        this.summarizer = summarizer;
    }

    private static int maxMessages(int tokenBudget) {
        return Math.max(1, Math.min(MAX_WINDOW, tokenBudget / MIN_TOKENS_PER_MESSAGE));
    }

    public ContextResult context(String workspace, String sessionName, int tokenBudget) {
        // Named, not bare. A bare orElseThrow raises NoSuchElementException, which the API's catch-all
        // reports as a 500 and logs at ERROR — a caller's typo indistinguishable from a server fault,
        // in both the response and the error-rate metric.
        Session session =
                sessions
                        .find(workspace, sessionName)
                        .orElseThrow(() -> new NotFoundException("session", sessionName));

        int summaryBudget = (int) Math.floor(tokenBudget * SUMMARY_SHARE);
        Optional<Summary> chosen = chooseSummary(session, summaryBudget);
        String summaryText = chosen.map(Summary::text).orElse("");
        int summaryTokens = chosen.map(Summary::tokenCount).orElse(0);

        // Anything the summary already covers is not repeated verbatim.
        long from = chosen.map(s -> s.coversThroughSeq() + 1).orElse(1L);
        int messageBudget = tokenBudget - summaryTokens;

        // The newest messages after the summary, not the oldest. Reading forward from the summary's
        // end point meant that once a session outgrew one page, context() returned the middle of the
        // conversation and omitted everything since — the exact opposite of what this API is for, and
        // invisible because nothing failed.
        List<Message> window = messages.tailFrom(workspace, sessionName, from, maxMessages(tokenBudget));
        List<Message> kept = new ArrayList<>();
        int used = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            Message message = window.get(i);
            if (used + message.tokenCount() > messageBudget && !kept.isEmpty()) {
                break;
            }
            kept.add(message);
            used += message.tokenCount();
        }
        Collections.reverse(kept);

        long startSeq = kept.isEmpty() ? from : kept.get(0).seqInSession();
        return new ContextResult(summaryText, kept, startSeq, summaryTokens, used, tokenBudget);
    }

    /**
     * Prefer the long summary when it fits, otherwise the short one, otherwise none.
     *
     * <p>Not the other way round: the long summary covers more messages, so choosing it leaves fewer
     * to include verbatim and the total stays inside the budget while carrying more history.
     */
    private Optional<Summary> chooseSummary(Session session, int budget) {
        Optional<Summary> longSummary = summarizer.find(session, Summary.LONG);
        if (longSummary.isPresent() && longSummary.get().tokenCount() <= budget) {
            return longSummary;
        }
        Optional<Summary> shortSummary = summarizer.find(session, Summary.SHORT);
        if (shortSummary.isPresent() && shortSummary.get().tokenCount() <= budget) {
            return shortSummary;
        }
        return Optional.empty();
    }

    /**
     * The representation string a caller pastes into another model's prompt.
     *
     * @param perspective the observing peer, or null for the session's own view
     */
    public String render(ContextResult context, String target, String perspective) {
        StringBuilder sb = new StringBuilder();
        if (perspective == null) {
            sb.append("Conversation with ").append(target).append(".\n\n");
        } else {
            sb.append(perspective).append("'s view of ").append(target).append(".\n\n");
        }
        if (!context.summary().isBlank()) {
            sb.append("Earlier:\n").append(context.summary()).append("\n\n");
        }
        sb.append("Recent messages:\n");
        context.messages().forEach(
                m -> sb.append(m.peerName()).append(": ").append(m.content()).append('\n'));
        return sb.toString().stripTrailing();
    }
}
