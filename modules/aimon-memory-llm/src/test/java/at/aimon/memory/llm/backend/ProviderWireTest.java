package at.aimon.memory.llm.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import at.aimon.memory.core.spi.llm.ResponseFormat;

/**
 * What actually goes on the wire.
 *
 * <p>A real server on an ephemeral port rather than a stubbed {@link java.net.http.HttpClient}: the
 * thing under test is the request body, and a stub that intercepts before serialisation would assert
 * on the object graph instead of on what the provider receives.
 *
 * <p>These exist because the previous Anthropic body was a 400 waiting for a model upgrade. Nothing
 * in the suite looked at the request, so the schema-in-the-system-prompt workaround and its assistant
 * prefill — rejected outright from the 4.6 family onward — would have surfaced as every structured
 * call in the system failing at once, in production, on a configuration change.
 */
class ProviderWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],
             "additionalProperties":false}
            """;

    private HttpServer server;
    private final AtomicReference<JsonNode> captured = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] payload = anthropicStyleResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String anthropicStyleResponse() {
        return """
                {"model":"claude-opus-5",
                 "content":[{"type":"text","text":"{\\"answer\\":\\"seoul\\"}"}],
                 "usage":{"input_tokens":10,"output_tokens":5},
                 "choices":[{"message":{"content":"{\\"answer\\":\\"seoul\\"}"}}]}
                """;
    }

    private ChatCall structuredCall() {
        return new ChatCall(null, "You extract facts.", List.of(new ChatTurn.UserText("where does alice work")),
                List.of(), ResponseFormat.strict("answer", SCHEMA), 0.0, null);
    }

    @Test
    void anthropicDeclaresTheSchemaThroughOutputConfig() {
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        backend.chat(structuredCall());

        JsonNode body = captured.get();
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("required").get(0).asText())
                .isEqualTo("answer");
    }

    /**
     * The regression itself: no trailing assistant turn, and the schema is not smuggled into the
     * system prompt. Both were how the old implementation forced JSON, and a prefilled final
     * assistant turn is a 400 on every current model.
     */
    @Test
    void anthropicSendsNoAssistantPrefillAndLeavesTheSystemPromptAlone() {
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        backend.chat(structuredCall());

        JsonNode messages = captured.get().path("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(captured.get().path("system").asText()).isEqualTo("You extract facts.");
    }

    @Test
    void anthropicSendsTheVersionHeaderAndTheModel() {
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        var response = backend.chat(structuredCall());

        assertThat(captured.get().path("model").asText()).isEqualTo("claude-opus-5");
        assertThat(captured.get().has("max_tokens")).isTrue();
        assertThat(response.text()).contains("seoul");
    }

    /** Tools still arrive as {@code input_schema}, which the format parameter must not have displaced. */
    @Test
    void anthropicKeepsToolsAlongsideAStructuredFormat() {
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        backend.chat(new ChatCall(null, "system", List.of(new ChatTurn.UserText("hi")),
                List.of(new ToolSpec("recall", "look things up", SCHEMA)), ResponseFormat.strict("answer", SCHEMA), 0.0,
                null));

        JsonNode tools = captured.get().path("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("recall");
        assertThat(tools.get(0).path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(captured.get().path("messages")).hasSize(1);
    }

    /**
     * The other half of the same regression, and the one the earlier tests could not see.
     *
     * <p>They asserted on the schema and the messages while passing {@code temperature 0.0} to a stub
     * server that accepts anything. Sampling parameters were removed from Opus 5, Opus 4.8, Opus 4.7,
     * Sonnet 5 and Fable 5 — sending one is a 400, classified here as {@code llm_rejected}, which the
     * fallback chain deliberately does not retry. Every caller in the system asks for {@code 0.0}, so
     * pointing the default at {@code claude-opus-5} would have taken extraction, summarisation,
     * dreaming and chat down together.
     */
    @Test
    void anthropicOmitsTemperatureOnModelsThatRejectIt() {
        for (String model : List.of("claude-opus-5", "claude-opus-4-8", "claude-sonnet-5", "claude-fable-5")) {
            new AnthropicChatBackend(baseUrl(), "k", model, Duration.ofSeconds(5)).chat(structuredCall());
            assertThat(captured.get().has("temperature")).as("%s rejects sampling parameters with a 400", model)
                    .isFalse();
        }
    }

    /** And still sends it where it is accepted, because determinism is worth having where it exists. */
    @Test
    void anthropicKeepsTemperatureOnModelsThatStillTakeIt() {
        new AnthropicChatBackend(baseUrl(), "k", "claude-sonnet-4-6", Duration.ofSeconds(5)).chat(structuredCall());

        assertThat(captured.get().path("temperature").asDouble()).isZero();
    }

    /**
     * A cap a structured call can reach mid-object is a parse failure, not a truncated answer.
     *
     * <p>Thinking tokens are billed against {@code max_tokens} and the current models think adaptively
     * by default, so 4096 returned {@code stop_reason: max_tokens} holding half a JSON document —
     * {@code output_config.format} only guarantees the shape of a response that finished.
     */
    @Test
    void anthropicGivesStructuredCallsRoomToFinish() {
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        backend.chat(structuredCall());
        assertThat(captured.get().path("max_tokens").asInt()).isGreaterThanOrEqualTo(16_000);

        backend.stream(structuredCall()).forEach(chunk -> {
        });
        assertThat(captured.get().path("max_tokens").asInt()).isGreaterThanOrEqualTo(16_000);

        // A caller that names its own budget still gets exactly that.
        backend.chat(new ChatCall(null, "system", List.of(new ChatTurn.UserText("hi")), List.of(), null, 0.0, 256));
        assertThat(captured.get().path("max_tokens").asInt()).isEqualTo(256);
    }

    @Test
    void openAiDeclaresAStrictJsonSchema() {
        OpenAiChatBackend backend = new OpenAiChatBackend(baseUrl(), "k", "gpt-4.1-mini", Duration.ofSeconds(5));

        backend.chat(structuredCall());

        JsonNode format = captured.get().path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo("answer");
    }
}
