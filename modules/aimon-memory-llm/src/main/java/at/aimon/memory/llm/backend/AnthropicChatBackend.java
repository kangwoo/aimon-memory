package at.aimon.memory.llm.backend;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.memory.core.spi.llm.LlmUsage;
import at.aimon.memory.core.spi.llm.ResponseFormat;
import at.aimon.memory.llm.Json;
import at.aimon.memory.llm.LlmException;

/**
 * Anthropic messages.
 *
 * <p>Structured output is declared, not coaxed: {@code output_config.format} with a
 * {@code json_schema} constrains the response the way OpenAI's {@code response_format} does.
 *
 * <p>This replaced the older trick of appending the schema to the system prompt and prefilling the
 * assistant turn with an opening brace. That worked when the API had no format parameter, and it has
 * since become actively harmful: a prefilled final assistant turn is <b>rejected with a 400</b> on
 * every model from the 4.6 family onward. Pointing {@code AIMON_MEMORY_ANTHROPIC_MODEL} at a current model
 * would have failed every structured call in the system — the deriver, the summariser and the
 * dreamer are all structured — and the failure could not be absorbed by the fallback chain either,
 * since a 400 is {@code llm_rejected} and deliberately aborts the plan rather than retrying it.
 */
public final class AnthropicChatBackend implements ChatBackend {

    private static final String API_VERSION = "2023-06-01";

    /**
     * The output ceiling a call gets when it names none.
     *
     * <p>Two numbers, because the two paths are bounded by different things. Thinking tokens are
     * billed against {@code max_tokens} and the current models think adaptively unless told not to,
     * so a single flat 4096 was a cap a structured call reached <em>mid-object</em>: the response
     * comes back {@code stop_reason: max_tokens} holding half a JSON document, {@code
     * output_config.format} guarantees the shape only for a response that finished, and {@code
     * LlmClient.structured} then fails to parse — a work unit that fails and eventually quarantines,
     * for no reason a reader of the logs could see. A blocking call still has to land inside the
     * HTTP timeout, so it gets 16k; a stream has no such deadline and gets room to finish.
     */
    private static final int DEFAULT_MAX_TOKENS = 16_000;

    private static final int DEFAULT_MAX_TOKENS_STREAMING = 64_000;

    /**
     * Model families that still accept {@code temperature}, {@code top_p} and {@code top_k}.
     *
     * <p>An allowlist, and deliberately not the other way round. Sampling parameters were
     * <b>removed</b> from Opus 5, Opus 4.8, Opus 4.7, Sonnet 5 and Fable 5: sending one is a 400,
     * which this pipeline classifies as {@code llm_rejected} and the fallback chain deliberately
     * does not retry. Every call in the system asks for {@code temperature 0.0} — the deriver, the
     * summariser, the dialectic, the dreamer and the peer card all do — so with the default model
     * now {@code claude-opus-5}, a denylist that had not heard of the next model would take
     * extraction, summarisation, dreaming and chat down together on the day it shipped.
     *
     * <p>Failing the other way costs a model sampling at its own default instead of greedily, which
     * is a small loss of determinism on one call rather than an outage. On the models that removed
     * it, {@code output_config.effort} is the lever that replaced it.
     */
    private static final List<String> SAMPLING_MODEL_PREFIXES = List.of("claude-opus-4-6", "claude-opus-4-5",
            "claude-sonnet-4-6", "claude-sonnet-4-5", "claude-haiku-4-5", "claude-3", "claude-2");

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public AnthropicChatBackend(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    public AnthropicChatBackend(String baseUrl, String apiKey, String model, Duration timeout, HttpClient http) {
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
        ObjectNode body = body(call, false);
        JsonNode root = HttpSupport.send(http,
                HttpSupport.request(baseUrl + "/v1/messages", headers(), timeout, body.toString()).build(),
                "anthropic");

        StringBuilder text = new StringBuilder();
        List<ToolUse> uses = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "tool_use" -> uses.add(new ToolUse(block.path("id").asText(), block.path("name").asText(),
                        block.path("input").toString()));
                default -> {
                    // thinking and other block types carry no payload this pipeline consumes
                }
            }
        }
        String answer = text.toString();
        return new ChatResponse(answer.isEmpty() ? null : answer, uses, root.path("model").asText(call.model()),
                new LlmUsage(root.path("usage").path("input_tokens").asInt(),
                        root.path("usage").path("output_tokens").asInt()),
                root.toString());
    }

    @Override
    public Stream<String> stream(ChatCall call) {
        ObjectNode body = body(call, true);
        return HttpSupport
                .sendLines(http,
                        HttpSupport.request(baseUrl + "/v1/messages", headers(), timeout, body.toString()).build(),
                        "anthropic")
                .filter(line -> line.startsWith("data: ")).map(line -> Json.read(line.substring(6)))
                .map(AnthropicChatBackend::failOnErrorEvent)
                .filter(node -> "content_block_delta".equals(node.path("type").asText()))
                .map(node -> node.path("delta").path("text").asText("")).filter(text -> !text.isEmpty());
    }

    /**
     * Raise an error the provider reported mid-stream instead of filtering it away.
     *
     * <p>A stream can fail after its headers have been accepted — an overload downstream of the
     * gateway is the usual way — and it says so with an {@code error} event in the body. The
     * {@code content_block_delta} filter below drops every event that is not a delta, which meant
     * that one arrived, was discarded, and the stream then ended normally: the caller received a
     * truncated answer followed by {@code event: done}, and nothing anywhere recorded that the
     * provider had given up. A short answer is indistinguishable from a complete one.
     *
     * <p>Its own code rather than {@code llm_retryable}. By the time this runs the stream has already
     * been handed back through the fallback chain and is being consumed lazily, so there is no
     * attempt left for a retryable code to trigger — saying so plainly is more useful than a label
     * that promises a retry nothing will perform.
     */
    private static JsonNode failOnErrorEvent(JsonNode event) {
        if (!"error".equals(event.path("type").asText())) {
            return event;
        }
        JsonNode error = event.path("error");
        throw new LlmException("llm_stream_error", "anthropic ended the stream with "
                + error.path("type").asText("an error") + ": " + error.path("message").asText(""));
    }

    private ObjectNode body(ChatCall call, boolean stream) {
        ObjectNode body = Json.object();
        String resolvedModel = call.model() == null ? model : call.model();
        body.put("model", resolvedModel);
        body.put("max_tokens",
                call.maxTokens() == null
                        ? (stream ? DEFAULT_MAX_TOKENS_STREAMING : DEFAULT_MAX_TOKENS)
                        : call.maxTokens());
        if (call.temperature() != null && acceptsSamplingParameters(resolvedModel)) {
            body.put("temperature", call.temperature());
        }

        if (call.system() != null && !call.system().isBlank()) {
            body.put("system", call.system());
        }

        ResponseFormat format = call.responseFormat();
        if (format != null) {
            ObjectNode outputFormat = body.putObject("output_config").putObject("format");
            outputFormat.put("type", "json_schema");
            outputFormat.set("schema", Json.read(format.jsonSchema()));
        }

        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : call.turns()) {
            appendTurn(messages, turn);
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

    /**
     * Whether this model still takes {@code temperature}.
     *
     * <p>Prefix rather than equality: a pinned snapshot such as {@code claude-sonnet-4-5-20250929}
     * has the same request surface as the family it belongs to, and an unknown name — a gateway's
     * own alias, a model released after this list was written — falls through to "do not send it",
     * which is the side that degrades rather than fails.
     */
    static boolean acceptsSamplingParameters(String model) {
        if (model == null) {
            return false;
        }
        String normalised = model.toLowerCase(java.util.Locale.ROOT);
        return SAMPLING_MODEL_PREFIXES.stream().anyMatch(normalised::startsWith);
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
