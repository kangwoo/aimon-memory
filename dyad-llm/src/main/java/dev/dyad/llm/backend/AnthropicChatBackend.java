package dev.dyad.llm.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dyad.core.spi.llm.LlmUsage;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.llm.Json;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Anthropic messages.
 *
 * <p>There is no {@code response_format} here, so structured output is coaxed rather than declared:
 * the schema is appended to the system prompt and the assistant turn is prefilled with an opening
 * brace, which leaves the model no grammatical room to start with prose.
 *
 * <p>The prefill is skipped whenever tools are present. A prefilled assistant turn forbids the model
 * from opening with a {@code tool_use} block, so combining the two silently disables tool calling.
 */
public final class AnthropicChatBackend implements ChatBackend {

    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public AnthropicChatBackend(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    public AnthropicChatBackend(
            String baseUrl, String apiKey, String model, Duration timeout, HttpClient http) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.http = http;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    @Override
    public ChatResponse chat(ChatCall call) {
        boolean prefilled = shouldPrefill(call);
        ObjectNode body = body(call, prefilled, false);
        JsonNode root =
                HttpSupport.send(
                        http,
                        HttpSupport.request(baseUrl + "/v1/messages", headers(), timeout, body.toString()).build(),
                        "anthropic");

        StringBuilder text = new StringBuilder();
        List<ToolUse> uses = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "tool_use" ->
                        uses.add(
                                new ToolUse(
                                        block.path("id").asText(),
                                        block.path("name").asText(),
                                        block.path("input").toString()));
                default -> {
                    // thinking and other block types carry no payload this pipeline consumes
                }
            }
        }
        String answer = text.toString();
        if (prefilled && !answer.isEmpty()) {
            answer = "{" + answer;
        }
        return new ChatResponse(
                answer.isEmpty() ? null : answer,
                uses,
                root.path("model").asText(call.model()),
                new LlmUsage(
                        root.path("usage").path("input_tokens").asInt(),
                        root.path("usage").path("output_tokens").asInt()),
                root.toString());
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        ObjectNode body = body(call, false, true);
        return HttpSupport.sendLines(
                        http,
                        HttpSupport.request(baseUrl + "/v1/messages", headers(), timeout, body.toString()).build(),
                        "anthropic")
                .filter(line -> line.startsWith("data: "))
                .map(line -> Json.read(line.substring(6)))
                .filter(node -> "content_block_delta".equals(node.path("type").asText()))
                .map(node -> node.path("delta").path("text").asText(""))
                .filter(text -> !text.isEmpty());
    }

    private static boolean shouldPrefill(ChatCall call) {
        return call.responseFormat() != null && call.tools().isEmpty();
    }

    private ObjectNode body(ChatCall call, boolean prefill, boolean stream) {
        ObjectNode body = Json.object();
        body.put("model", call.model() == null ? model : call.model());
        body.put("max_tokens", call.maxTokens() == null ? DEFAULT_MAX_TOKENS : call.maxTokens());
        if (call.temperature() != null) {
            body.put("temperature", call.temperature());
        }

        String system = call.system() == null ? "" : call.system();
        ResponseFormat format = call.responseFormat();
        if (format != null) {
            system =
                    (system.isBlank() ? "" : system + "\n\n")
                            + "Answer with a single JSON object and nothing else. It must validate against"
                            + " this schema:\n"
                            + format.jsonSchema();
        }
        if (!system.isBlank()) {
            body.put("system", system);
        }

        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : call.turns()) {
            appendTurn(messages, turn);
        }
        if (prefill) {
            ObjectNode assistant = Json.object();
            assistant.put("role", "assistant");
            ArrayNode content = assistant.putArray("content");
            ObjectNode block = Json.object();
            block.put("type", "text");
            block.put("text", "{");
            content.add(block);
            messages.add(assistant);
        }

        if (!call.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : call.tools()) {
                ObjectNode tool = Json.object();
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                tool.set("input_schema", Json.read(spec.parametersSchema()));
                tools.add(tool);
            }
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private static void appendTurn(ArrayNode messages, ChatTurn turn) {
        switch (turn) {
            case ChatTurn.UserText t -> messages.add(textMessage("user", t.text()));
            case ChatTurn.AssistantText t -> messages.add(textMessage("assistant", t.text()));
            case ChatTurn.AssistantToolUse t -> {
                ObjectNode node = Json.object();
                node.put("role", "assistant");
                ArrayNode content = node.putArray("content");
                if (t.text() != null && !t.text().isBlank()) {
                    ObjectNode text = Json.object();
                    text.put("type", "text");
                    text.put("text", t.text());
                    content.add(text);
                }
                for (ToolUse use : t.uses()) {
                    ObjectNode block = Json.object();
                    block.put("type", "tool_use");
                    block.put("id", use.id());
                    block.put("name", use.name());
                    block.set("input", Json.read(use.argumentsJson()));
                    content.add(block);
                }
                messages.add(node);
            }
            case ChatTurn.ToolResults t -> {
                ObjectNode node = Json.object();
                node.put("role", "user");
                ArrayNode content = node.putArray("content");
                for (ToolResult result : t.results()) {
                    ObjectNode block = Json.object();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", result.toolUseId());
                    block.put("content", result.content());
                    if (result.isError()) {
                        block.put("is_error", true);
                    }
                    content.add(block);
                }
                messages.add(node);
            }
        }
    }

    private static ObjectNode textMessage(String role, String text) {
        ObjectNode node = Json.object();
        node.put("role", role);
        node.put("content", text);
        return node;
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }
}
