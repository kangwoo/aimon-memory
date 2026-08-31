package dev.dyad.llm.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dyad.core.spi.llm.LlmUsage;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.llm.Json;
import dev.dyad.llm.LlmException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * OpenAI chat completions.
 *
 * <p>Structured output goes through {@code response_format: json_schema} with {@code strict: true},
 * which is the only mode that actually guarantees the shape — JSON mode alone promises valid JSON,
 * not the schema you asked for.
 */
public final class OpenAiChatBackend implements ChatBackend {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public OpenAiChatBackend(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    public OpenAiChatBackend(
            String baseUrl, String apiKey, String model, Duration timeout, HttpClient http) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.http = http;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    @Override
    public ChatResponse chat(ChatCall call) {
        ObjectNode body = body(call, false);
        JsonNode root =
                HttpSupport.send(
                        http,
                        HttpSupport.request(baseUrl + "/chat/completions", headers(), timeout, body.toString()).build(),
                        "openai");
        JsonNode message = root.path("choices").path(0).path("message");
        List<ToolUse> uses = new ArrayList<>();
        for (JsonNode call2 : message.path("tool_calls")) {
            uses.add(
                    new ToolUse(
                            call2.path("id").asText(),
                            call2.path("function").path("name").asText(),
                            call2.path("function").path("arguments").asText("{}")));
        }
        return new ChatResponse(
                message.hasNonNull("content") ? message.get("content").asText() : null,
                uses,
                root.path("model").asText(call.model()),
                new LlmUsage(
                        root.path("usage").path("prompt_tokens").asInt(),
                        root.path("usage").path("completion_tokens").asInt()),
                root.toString());
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        ObjectNode body = body(call, true);
        return HttpSupport.sendLines(
                        http,
                        HttpSupport.request(baseUrl + "/chat/completions", headers(), timeout, body.toString()).build(),
                        "openai")
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6))
                .takeWhile(payload -> !"[DONE]".equals(payload))
                .map(payload -> Json.read(payload).path("choices").path(0).path("delta").path("content").asText(""))
                .filter(text -> !text.isEmpty());
    }

    private ObjectNode body(ChatCall call, boolean stream) {
        ObjectNode body = Json.object();
        body.put("model", call.model() == null ? model : call.model());
        ArrayNode messages = body.putArray("messages");
        if (call.system() != null && !call.system().isBlank()) {
            ObjectNode system = Json.object();
            system.put("role", "system");
            system.put("content", call.system());
            messages.add(system);
        }
        for (ChatTurn turn : call.turns()) {
            appendTurn(messages, turn);
        }
        if (!call.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : call.tools()) {
                ObjectNode tool = Json.object();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", Json.read(spec.parametersSchema()));
                tools.add(tool);
            }
        }
        ResponseFormat format = call.responseFormat();
        if (format != null) {
            ObjectNode rf = body.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode schema = rf.putObject("json_schema");
            schema.put("name", format.name());
            schema.put("strict", format.strict());
            schema.set("schema", Json.read(format.jsonSchema()));
        }
        if (call.temperature() != null) {
            body.put("temperature", call.temperature());
        }
        if (call.maxTokens() != null) {
            body.put("max_completion_tokens", call.maxTokens());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private static void appendTurn(ArrayNode messages, ChatTurn turn) {
        switch (turn) {
            case ChatTurn.UserText t -> {
                ObjectNode node = Json.object();
                node.put("role", "user");
                node.put("content", t.text());
                messages.add(node);
            }
            case ChatTurn.AssistantText t -> {
                ObjectNode node = Json.object();
                node.put("role", "assistant");
                node.put("content", t.text());
                messages.add(node);
            }
            case ChatTurn.AssistantToolUse t -> {
                ObjectNode node = Json.object();
                node.put("role", "assistant");
                if (t.text() != null && !t.text().isBlank()) {
                    node.put("content", t.text());
                } else {
                    node.putNull("content");
                }
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolUse use : t.uses()) {
                    ObjectNode c = Json.object();
                    c.put("id", use.id());
                    c.put("type", "function");
                    ObjectNode fn = c.putObject("function");
                    fn.put("name", use.name());
                    fn.put("arguments", use.argumentsJson());
                    calls.add(c);
                }
                messages.add(node);
            }
            case ChatTurn.ToolResults t -> {
                for (ToolResult result : t.results()) {
                    ObjectNode node = Json.object();
                    node.put("role", "tool");
                    node.put("tool_call_id", result.toolUseId());
                    node.put("content", result.content());
                    messages.add(node);
                }
            }
        }
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    /** Guards against a misconfiguration that only shows up as a 401 much later. */
    public void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("llm_auth", "OpenAI API key is not configured");
        }
    }
}
