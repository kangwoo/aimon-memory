package at.aimon.memory.llm.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.memory.core.spi.llm.LlmUsage;
import at.aimon.memory.llm.Json;
import at.aimon.memory.llm.LlmMode;
import at.aimon.memory.llm.backend.ChatBackend;
import at.aimon.memory.llm.backend.ChatCall;
import at.aimon.memory.llm.backend.ChatResponse;
import at.aimon.memory.llm.backend.ToolUse;

/**
 * The record/replay decorator.
 *
 * <p>It wraps the provider primitive rather than the client, which is what lets a ten-step tool loop
 * replay without the harness modelling loops at all: each step is its own content-addressed call, and
 * a step whose inputs are unchanged finds its own fixture.
 */
public final class RecordingChatBackend implements ChatBackend {

    private final ChatBackend delegate;
    private final LlmFixtureStore fixtures;
    private final LlmMode mode;

    public RecordingChatBackend(ChatBackend delegate, LlmFixtureStore fixtures, LlmMode mode) {
        this.delegate = delegate;
        this.fixtures = fixtures;
        this.mode = mode;
    }

    public static RecordingChatBackend around(ChatBackend delegate) {
        return new RecordingChatBackend(delegate, LlmFixtureStore.fromEnvironment(), LlmMode.fromEnvironment());
    }

    @Override
    public ChatResponse chat(ChatCall rawCall) {
        ChatCall call = resolve(rawCall);
        String key = FixtureKey.of(call);
        if (mode == LlmMode.REPLAY) {
            LlmFixture fixture = fixtures.find(key)
                    .orElseThrow(() -> new FixtureMissException(key, FixtureKey.canonical(call), fixtures.directory()));
            return deserialise(fixture.responseJson());
        }
        ChatResponse response = delegate.chat(call);
        if (mode == LlmMode.RECORD) {
            fixtures.write(key, FixtureKey.canonical(call), serialise(response), null);
        }
        return response;
    }

    @Override
    public Stream<String> stream(ChatCall rawCall) {
        ChatCall call = resolve(rawCall);
        // Keyed as a streaming call, so this can never collide with the blocking recording of the
        // same request — see FixtureKey for what sharing one key did.
        String key = FixtureKey.of(call, true);
        if (mode == LlmMode.REPLAY) {
            LlmFixture fixture = fixtures.find(key).orElseThrow(
                    () -> new FixtureMissException(key, FixtureKey.canonical(call, true), fixtures.directory()));
            List<String> chunks = fixture.streamChunks();
            if (chunks == null) {
                // Belt and braces now that the key separates them: a fixture found under a streaming
                // key with no chunks in it is a corrupt recording, and an empty stream would hide that
                // behind a response that completed successfully having said nothing.
                throw new FixtureMissException(key, FixtureKey.canonical(call, true), fixtures.directory());
            }
            return chunks.stream();
        }
        if (mode == LlmMode.LIVE) {
            return delegate.stream(call);
        }
        // Recording a stream means consuming it, so the caller gets a replay of what was captured.
        List<String> chunks = delegate.stream(call).toList();
        fixtures.write(key, FixtureKey.canonical(call, true), null, chunks);
        return chunks.stream();
    }

    /**
     * A null model means "whatever this backend defaults to". Hashing that as null would key one
     * fixture for two different models the moment a fallback chain has more than one provider, so the
     * default is substituted before the key is computed.
     */
    private ChatCall resolve(ChatCall call) {
        return call.model() == null ? call.withModel(delegate.defaultModel()) : call;
    }

    private static String serialise(ChatResponse response) {
        ObjectNode node = Json.object();
        node.put("text", response.text());
        node.put("model", response.model());
        ObjectNode usage = node.putObject("usage");
        usage.put("prompt_tokens", response.usage().promptTokens());
        usage.put("completion_tokens", response.usage().completionTokens());
        ArrayNode uses = node.putArray("tool_uses");
        for (ToolUse use : response.toolUses()) {
            ObjectNode u = Json.object();
            u.put("id", use.id());
            u.put("name", use.name());
            u.put("arguments", use.argumentsJson());
            uses.add(u);
        }
        if (response.rawJson() != null) {
            node.put("raw", response.rawJson());
        }
        return node.toString();
    }

    private static ChatResponse deserialise(String json) {
        JsonNode node = Json.read(json);
        List<ToolUse> uses = new ArrayList<>();
        for (JsonNode u : node.path("tool_uses")) {
            uses.add(new ToolUse(u.path("id").asText(), u.path("name").asText(), u.path("arguments").asText()));
        }
        return new ChatResponse(node.hasNonNull("text") ? node.get("text").asText() : null, uses,
                node.path("model").asText(),
                new LlmUsage(node.path("usage").path("prompt_tokens").asInt(),
                        node.path("usage").path("completion_tokens").asInt()),
                node.hasNonNull("raw") ? node.get("raw").asText() : null);
    }

    @Override
    public String defaultModel() {
        return delegate.defaultModel();
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
