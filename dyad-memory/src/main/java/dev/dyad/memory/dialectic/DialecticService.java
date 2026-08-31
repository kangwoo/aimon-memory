package dev.dyad.memory.dialectic;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmMessage;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.core.spi.llm.ToolLoopResult;
import dev.dyad.memory.prompt.Prompts;
import dev.dyad.text.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Tier 2: the agentic path, for questions Tier 1 cannot answer — enumerations, contradictions,
 * anything narrative.
 *
 * <p>Tier 1 is one of its tools, which is the arrangement the whole design is arguing for. The point
 * was never to remove the agent; it was to stop paying for one on questions that are lookups, and to
 * give the agent a good enough retriever that it stops iterating so much when it is genuinely needed.
 */
@Service
public class DialecticService {

    /** Above this the history is trimmed from the front. Keeps a long chat from outgrowing the window. */
    public static final int MAX_HISTORY_TOKENS = 8_000;

    /** A single question is capped separately; the limit protects the tool loop, not the model. */
    public static final int MAX_QUESTION_TOKENS = 4_000;

    private final LlmClient llm;
    private final ToolRegistry tools;

    public DialecticService(LlmClient llm, ToolRegistry tools) {
        this.llm = llm;
        this.tools = tools;
    }

    /**
     * @param history prior turns of this chat, oldest first
     * @param responseFormatSchema optional caller-supplied JSON Schema, validated before use
     */
    public record Question(
            PairKey pair,
            String sessionName,
            String text,
            List<LlmMessage> history,
            ReasoningLevel level,
            String responseFormatSchema) {

        public Question {
            history = history == null ? List.of() : List.copyOf(history);
            level = level == null ? ReasoningLevel.MEDIUM : level;
        }
    }

    public ToolLoopResult answer(Question question) {
        LlmRequest request = requestFor(question);
        List<ToolDef> toolset =
                tools.toolsFor(question.pair(), question.sessionName(), question.level().toolNames());
        return llm.toolLoop(request, toolset, question.level().maxIterations());
    }

    /**
     * Streaming variant.
     *
     * <p>Tools run first and the answer streams afterwards. Interleaving them would mean emitting
     * tokens the model may retract once a tool comes back, and a user watching text appear and then
     * change is worse than one watching a short pause.
     */
    public Stream<String> answerStreaming(Question question) {
        ToolLoopResult resolved = answer(question);
        if (resolved.text() != null && !resolved.text().isBlank()) {
            return Stream.of(resolved.text());
        }
        return llm.stream(requestFor(question));
    }

    private LlmRequest requestFor(Question question) {
        String text = TokenCounter.truncate(question.text(), MAX_QUESTION_TOKENS);
        List<LlmMessage> messages = new ArrayList<>(trimHistory(question.history()));
        messages.add(LlmMessage.user(text));

        ResponseFormat format =
                question.responseFormatSchema() == null
                        ? null
                        : ResponseFormat.strict(
                                "dialectic_answer", ResponseSchemaGuard.validate(question.responseFormatSchema()));

        return new LlmRequest(
                null,
                Prompts.dialectic(question.pair().observer(), question.pair().observed()),
                messages,
                0.0,
                null,
                format);
    }

    /**
     * Drop the oldest turns until the history fits.
     *
     * <p>From the front, because the recent turns are what the current question refers back to. A
     * budget that trims the tail loses the antecedent of "what about the other one".
     */
    private static List<LlmMessage> trimHistory(List<LlmMessage> history) {
        int total = history.stream().mapToInt(m -> TokenCounter.count(m.content())).sum();
        if (total <= MAX_HISTORY_TOKENS) {
            return history;
        }
        List<LlmMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            int cost = TokenCounter.count(history.get(i).content());
            if (used + cost > MAX_HISTORY_TOKENS) {
                break;
            }
            kept.add(0, history.get(i));
            used += cost;
        }
        return List.copyOf(kept);
    }
}
