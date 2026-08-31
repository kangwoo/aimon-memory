package dev.dyad.llm.backend;

import dev.dyad.llm.LlmException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Walks an {@link AttemptPlan} until something answers.
 *
 * <p>Not every failure deserves another attempt. A malformed request ({@code llm_rejected}) will be
 * malformed at the next provider too, so it aborts the plan rather than burning through it; an auth
 * failure is worth a fallback, since it usually means one key is misconfigured, not both.
 */
public final class FallbackChatBackend implements ChatBackend {

    private final AttemptPlan plan;
    private final long baseBackoffMillis;

    public FallbackChatBackend(AttemptPlan plan) {
        this(plan, 200);
    }

    public FallbackChatBackend(AttemptPlan plan, long baseBackoffMillis) {
        this.plan = plan;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    public static FallbackChatBackend of(ChatBackend primary, ChatBackend... others) {
        return new FallbackChatBackend(
                AttemptPlan.of(Stream.concat(Stream.of(primary), Stream.of(others)).toList(), 2));
    }

    @Override
    public ChatResponse chat(ChatCall call) {
        return run(attempt -> attempt.backend().chat(call));
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        return run(attempt -> attempt.backend().stream(call));
    }

    private <T> T run(java.util.function.Function<AttemptPlan.Attempt, T> action) {
        LlmException last = null;
        List<AttemptPlan.Attempt> attempts = plan.attempts();
        for (int i = 0; i < attempts.size(); i++) {
            AttemptPlan.Attempt attempt = attempts.get(i);
            if (attempt.attemptWithinBackend() > 0) {
                backoff(attempt.attemptWithinBackend());
            }
            try {
                return action.apply(attempt);
            } catch (LlmException e) {
                if ("llm_rejected".equals(e.code())) {
                    throw e;
                }
                last = e;
            }
        }
        throw new LlmException(
                "llm_exhausted",
                "all " + plan.size() + " attempts failed; last: " + (last == null ? "unknown" : last.getMessage()));
    }

    private void backoff(int attemptWithinBackend) {
        long ceiling = baseBackoffMillis << Math.min(attemptWithinBackend, 8);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(baseBackoffMillis, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm_interrupted", "interrupted while backing off");
        }
    }

    @Override
    public String defaultModel() {
        return plan.attempts().get(0).backend().defaultModel();
    }

    @Override
    public String providerName() {
        return "fallback(" + plan.attempts().get(0).backend().providerName() + ")";
    }
}
