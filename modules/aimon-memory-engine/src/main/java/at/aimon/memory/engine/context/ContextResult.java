package at.aimon.memory.engine.context;

import java.util.List;

import at.aimon.memory.core.model.Message;

/**
 * Tier 0 output: what to paste into a prompt.
 *
 * @param messagesStartSeq the first sequence number included, so a caller can ask for the next page
 *     without re-deriving the budget
 */
public record ContextResult(String summary, List<Message> messages, long messagesStartSeq, int summaryTokens,
        int messageTokens, int tokenBudget) {

    public ContextResult {
        messages = List.copyOf(messages);
    }

    public int totalTokens() {
        return summaryTokens + messageTokens;
    }
}
