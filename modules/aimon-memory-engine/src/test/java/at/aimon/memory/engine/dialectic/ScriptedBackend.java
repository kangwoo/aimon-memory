package at.aimon.memory.engine.dialectic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import at.aimon.memory.core.spi.llm.LlmUsage;
import at.aimon.memory.llm.LlmException;
import at.aimon.memory.llm.backend.ChatBackend;
import at.aimon.memory.llm.backend.ChatCall;
import at.aimon.memory.llm.backend.ChatResponse;
import at.aimon.memory.llm.backend.ToolUse;

/** A provider that answers from a script, and can be told to refuse every call. */
final class ScriptedBackend implements ChatBackend {

    private final Deque<ChatResponse> script = new ArrayDeque<>();
    private final List<ChatCall> calls = new ArrayList<>();
    private boolean refusing;

    ScriptedBackend requestingTool(String id, String name, String argumentsJson) {
        script.add(new ChatResponse(null, List.of(new ToolUse(id, name, argumentsJson)), "scripted",
                new LlmUsage(10, 5), "{}"));
        return this;
    }

    ScriptedBackend answering(String text) {
        script.add(new ChatResponse(text, List.of(), "scripted", new LlmUsage(10, 5), "{}"));
        return this;
    }

    /** Any call is a failure — used to prove replay never reaches the provider. */
    ScriptedBackend refusing() {
        this.refusing = true;
        return this;
    }

    @Override
    public ChatResponse chat(ChatCall call) {
        if (refusing) {
            throw new LlmException("provider_called", "replay must not reach the provider");
        }
        calls.add(call);
        if (script.isEmpty()) {
            throw new LlmException("script_exhausted", "the script ran out after " + calls.size() + " calls");
        }
        return script.poll();
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        return Stream.of(chat(call).text());
    }

    @Override
    public String defaultModel() {
        return "scripted-model";
    }

    @Override
    public String providerName() {
        return "scripted";
    }

    List<ChatCall> calls() {
        return List.copyOf(calls);
    }
}
