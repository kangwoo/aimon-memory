package dev.dyad.memory.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.memory.MemoryTestBase;
import dev.dyad.memory.summarize.Summary;
import dev.dyad.memory.summarize.SummarizerService;
import dev.dyad.store.repo.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tier 0's budget arithmetic. Deterministic by construction, so a fixture can pin it. */
class ContextBudgetTest extends MemoryTestBase {

    private ContextService context;
    private SummarizerService summarizer;

    @BeforeEach
    void wire() {
        summarizer =
                new SummarizerService(
                        stub("{\"summary\":\"alice and bob discussed the project timeline.\"}"),
                        sessions, messages, CLOCK);
        context = new ContextService(sessions, messages, summarizer);
    }

    private void seedMessages(int count, int tokensEach) {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", count);
        List<MessageRepository.NewMessage> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new MessageRepository.NewMessage("alice", "message " + i, tokensEach, Map.of()));
        }
        messages.insertBatch(WORKSPACE, "s1", start, rows);
    }

    @Test
    void withNoSummaryTheWholeBudgetGoesToMessages() {
        seedMessages(10, 10);
        var result = context.context(WORKSPACE, "s1", 1000);

        assertThat(result.summary()).isEmpty();
        assertThat(result.summaryTokens()).isZero();
        assertThat(result.messages()).hasSize(10);
        assertThat(result.messageTokens()).isEqualTo(100);
        assertThat(result.messagesStartSeq()).isEqualTo(1);
    }

    /**
     * A tight budget drops the oldest, never the newest. The most recent turn is the one thing the
     * next answer is certainly about.
     */
    @Test
    void aTightBudgetDropsTheOldestMessagesFirst() {
        seedMessages(10, 10);
        var result = context.context(WORKSPACE, "s1", 50);

        assertThat(result.messages()).hasSize(5);
        assertThat(result.messages().get(0).content()).isEqualTo("message 5");
        assertThat(result.messages().get(4).content()).isEqualTo("message 9");
        assertThat(result.messagesStartSeq()).isEqualTo(6);
    }

    /** Even a budget too small for one message returns the latest one rather than nothing. */
    @Test
    void anImpossibleBudgetStillReturnsTheLatestMessage() {
        seedMessages(5, 100);
        var result = context.context(WORKSPACE, "s1", 10);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).isEqualTo("message 4");
    }

    /**
     * The summary gets 40% and covers the earlier messages, so those are not repeated verbatim. That
     * is the whole reason the two are budgeted together rather than separately.
     */
    @Test
    void aSummaryTakesItsShareAndSuppressesTheMessagesItCovers() {
        seedMessages(30, 10);
        summarizer.refresh(WORKSPACE, "s1");

        var session = sessions.find(WORKSPACE, "s1").orElseThrow();
        var summary = summarizer.find(session, Summary.SHORT).orElseThrow();
        assertThat(summary.coversThroughSeq()).isEqualTo(30);

        var result = context.context(WORKSPACE, "s1", 1000);
        assertThat(result.summary()).contains("timeline");
        assertThat(result.summaryTokens()).isGreaterThan(0);
        assertThat(result.messages()).isEmpty();
        assertThat(result.totalTokens()).isEqualTo(result.summaryTokens());
    }

    /** A summary that does not fit its share is skipped rather than blowing the budget. */
    @Test
    void anOversizedSummaryIsSkipped() {
        seedMessages(30, 10);
        summarizer.refresh(WORKSPACE, "s1");

        var result = context.context(WORKSPACE, "s1", 10);
        assertThat(result.summary()).isEmpty();
        assertThat(result.messages()).isNotEmpty();
    }

    /**
     * A long session must still show its most recent turns.
     *
     * <p>The window was read as the <em>oldest</em> page after the summary, so once a session ran past
     * that page {@code context()} returned messages from the middle of the conversation and silently
     * omitted everything since. Nothing failed and nothing was logged — the caller just got a stale
     * view, which for the one API whose entire job is "what was just said" is the worst possible way
     * to be wrong.
     */
    @Test
    void aLongSessionStillShowsItsMostRecentMessages() {
        seedMessages(2500, 10);

        var result = context.context(WORKSPACE, "s1", 200);

        assertThat(result.messages()).isNotEmpty();
        assertThat(result.messages().get(result.messages().size() - 1).content())
                .as("the newest message in the session")
                .isEqualTo("message 2499");
        assertThat(result.messages()).allSatisfy(
                m -> assertThat(m.seqInSession()).isGreaterThan(2480L));
    }

    @Test
    void renderedRepresentationNamesThePerspective() {
        seedMessages(3, 10);
        var result = context.context(WORKSPACE, "s1", 1000);

        assertThat(context.render(result, "alice", null)).startsWith("Conversation with alice.");
        assertThat(context.render(result, "alice", "bob")).startsWith("bob's view of alice.");
        assertThat(context.render(result, "alice", "bob")).contains("alice: message 0");
    }
}
