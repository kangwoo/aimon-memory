package dev.dyad.llm;

import dev.dyad.core.spi.llm.LlmUsage;
import dev.dyad.llm.backend.ChatBackend;
import dev.dyad.llm.backend.ChatCall;
import dev.dyad.llm.backend.ChatResponse;
import dev.dyad.llm.backend.ToolUse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/** A scripted backend. Records the calls it saw so a test can assert on the sequence. */
final class FakeChatBackend implements ChatBackend {

    private final String model;
    private final Deque<ChatResponse> scripted = new ArrayDeque<>();
    private final List<ChatCall> calls = new ArrayList<>();
    private RuntimeException failure;
    private int failuresRemaining;

    FakeChatBackend(String model) {
        this.model = model;
    }

    FakeChatBackend answering(String text) {
        scripted.add(new ChatResponse(text, List.of(), model, new LlmUsage(5, 5), "{}"));
        return this;
    }

    FakeChatBackend requestingTool(String id, String name, String argumentsJson) {
        scripted.add(
                new ChatResponse(
                        null, List.of(new ToolUse(id, name, argumentsJson)), model, new LlmUsage(5, 5), "{}"));
        return this;
    }

    FakeChatBackend failing(RuntimeException e, int times) {
        this.failure = e;
        this.failuresRemaining = times;
        return this;
    }

    @Override
    public ChatResponse chat(ChatCall call) {
        calls.add(call);
        if (failuresRemaining > 0) {
            failuresRemaining--;
            throw failure;
        }
        if (scripted.isEmpty()) {
            return new ChatResponse("done", List.of(), model, new LlmUsage(1, 1), "{}");
        }
        return scripted.poll();
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        calls.add(call);
        return Stream.of("hello", " world");
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public String providerName() {
        return "fake-" + model;
    }

    List<ChatCall> calls() {
        return List.copyOf(calls);
    }

    int callCount() {
        return calls.size();
    }
}
