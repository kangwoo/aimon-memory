package at.aimon.memory.llm.backend;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import at.aimon.memory.llm.LlmException;

/**
 * Walks an {@link AttemptPlan} until something answers.
 *
 * <p>Not every failure deserves another attempt. A malformed request ({@code llm_rejected}) will be
 * malformed at the next provider too, so it aborts the plan rather than burning through it; an auth
 * failure is worth a fallback, since it usually means one key is misconfigured, not both.
 */
public final class FallbackChatBackend implements ChatBackend {

    /**
     * Ceiling on a single backoff, matching the embedder's.
     *
     * <p>The doubling is not a bound on its own: {@code attemptsPerProvider} is configuration, and at
     * ten attempts the eighth doubling is fifty-one seconds of sleep inside a worker thread that is
     * holding a queue claim the whole time. {@code embed/Backoff} has capped this at twenty seconds
     * since it was written, and there was never a reason for the two to disagree.
     */
    private static final long MAX_BACKOFF_MILLIS = 20_000;

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
        // The last failure is attached as the cause, not only quoted into the message. Its own stack
        // is where the provider, the status code and the transport error actually are; summarising it
        // to a string threw all of that away at the one point where someone is asking why every
        // provider refused.
        //
        // `publicMessage`, not `getMessage`: this composes another exception's message into one that
        // becomes a 500 body, so it inherits whatever that one was written for. A fixture miss is a
        // diagnostic for a developer and carries the assembled prompt and an absolute server path —
        // and it arrives here whenever a second provider is configured, because
        // `MemoryConfiguration.llmClient` wraps each provider in `RecordingChatBackend` and composes
        // the wrapped ones. Quoting `getMessage` here would put back on the wire exactly what
        // `ApiExceptionHandler` refuses to, one indirection further along.
        throw new LlmException("llm_exhausted",
                "all " + plan.size() + " attempts failed; last: " + (last == null ? "unknown" : last.publicMessage()),
                last);
    }

    private void backoff(int attemptWithinBackend) {
        long doubled = baseBackoffMillis << Math.min(attemptWithinBackend, 8);
        // max(base, …) so that a base above the cap still leaves nextLong a legal range rather than
        // an inverted one.
        long ceiling = Math.max(baseBackoffMillis, Math.min(MAX_BACKOFF_MILLIS, doubled));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(baseBackoffMillis, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm_interrupted", "interrupted while backing off", e);
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
