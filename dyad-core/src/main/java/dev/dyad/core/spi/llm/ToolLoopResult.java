package dev.dyad.core.spi.llm;

import java.util.List;

/**
 * The outcome of an agentic loop.
 *
 * @param stoppedAtLimit true when the loop hit {@code maxIterations} with the model still asking for
 *     tools — the answer is then partial and callers should say so rather than present it as final
 */
public record ToolLoopResult(
        String text, int iterations, List<ToolCall> calls, LlmUsage usage, boolean stoppedAtLimit) {

    public ToolLoopResult {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }
}
