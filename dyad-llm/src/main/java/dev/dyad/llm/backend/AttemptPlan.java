package dev.dyad.llm.backend;

import dev.dyad.llm.LlmException;
import java.util.ArrayList;
import java.util.List;

/**
 * A flat, pre-computed list of attempts.
 *
 * <p>Flat is the whole idea. The obvious implementation — a retry loop wrapped around a fallback
 * loop — bounces back to the primary after every fallback, so a provider that is down gets hammered
 * once per fallback instead of being abandoned. Here the order is decided up front and only moves
 * forward: primary twice, then secondary twice, and never primary again.
 */
public record AttemptPlan(List<Attempt> attempts) {

    public record Attempt(ChatBackend backend, int attemptWithinBackend) {}

    public AttemptPlan {
        if (attempts.isEmpty()) {
            throw new LlmException("bad_attempt_plan", "an attempt plan needs at least one attempt");
        }
        attempts = List.copyOf(attempts);
    }

    public static AttemptPlan of(List<ChatBackend> backends, int attemptsPerBackend) {
        if (backends.isEmpty()) {
            throw new LlmException("bad_attempt_plan", "no backends configured");
        }
        if (attemptsPerBackend < 1) {
            throw new LlmException("bad_attempt_plan", "attemptsPerBackend must be at least 1");
        }
        List<Attempt> attempts = new ArrayList<>(backends.size() * attemptsPerBackend);
        for (ChatBackend backend : backends) {
            for (int i = 0; i < attemptsPerBackend; i++) {
                attempts.add(new Attempt(backend, i));
            }
        }
        return new AttemptPlan(attempts);
    }

    public int size() {
        return attempts.size();
    }
}
