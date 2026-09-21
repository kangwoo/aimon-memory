package at.aimon.memory.llm.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import at.aimon.memory.core.spi.llm.LlmUsage;
import at.aimon.memory.llm.LlmException;

/**
 * A provider answering in a shape this code cannot read must fail over, not fall out.
 *
 * <p>Jackson 3 redefined {@code asString}/{@code asInt} from "coerce to a zero value" to "coerce or
 * raise", and the raise is a {@code JsonNodeException} — a {@code RuntimeException} that is not an
 * {@link LlmException}. {@code FallbackChatBackend.run} catches {@code LlmException} and nothing
 * else, so before the backends wrapped their response shaping, a gateway answering
 * {@code content} as a parts array took the whole request down instead of handing it to the next
 * provider. Under Jackson 2 the same response read as an empty answer and never surfaced at all.
 *
 * <p>Neither of those is what this system promises. These tests pin the third behaviour: the
 * misshapen response is refused, with a code, on a path that still has somewhere to fall back to —
 * and they pin where that stops, because a provider's token count is not its answer.
 */
class ProviderShapeDriftTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
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

    private static ChatCall call() {
        return new ChatCall(null, "system", List.of(new ChatTurn.UserText("hi")), List.of(), null, 0.0, null);
    }

    /** The common OpenAI-compatible variant: `content` is a parts array rather than a string. */
    @Test
    void openAiContentAsAnArrayIsRefusedWithACode() {
        body.set("""
                {"model":"gpt-4.1-mini",
                 "choices":[{"message":{"content":[{"type":"text","text":"hi"}]}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """);
        OpenAiChatBackend backend = new OpenAiChatBackend(baseUrl(), "k", "gpt-4.1-mini", Duration.ofSeconds(5));

        assertThatThrownBy(() -> backend.chat(call())).isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).code()).isEqualTo("bad_json"));
    }

    /**
     * The line the strictness stops at. A token count is telemetry, so an unreadable one costs the
     * count and not the completion it arrived with — refusing here would spend a failover, a second
     * provider's bill and the caller's wait on a metric. {@code 0} is what an absent {@code usage}
     * block already reads as, so it is this code's existing word for "no count".
     */
    @Test
    void anthropicNonNumericUsageCostsTheCountAndNotTheAnswer() {
        body.set("""
                {"model":"claude-opus-5",
                 "content":[{"type":"text","text":"hi"}],
                 "usage":{"input_tokens":"n/a","output_tokens":5}}
                """);
        AnthropicChatBackend backend = new AnthropicChatBackend(baseUrl(), "k", "claude-opus-5", Duration.ofSeconds(5));

        ChatResponse response = backend.chat(call());

        assertThat(response.text()).isEqualTo("hi");
        assertThat(response.usage().promptTokens()).isZero();
        assertThat(response.usage().completionTokens()).isEqualTo(5);
    }

    /** A count typed as a numeric string is the common gateway sloppiness, and costs nothing. */
    @Test
    void openAiNumericStringUsageStillReadsAsTheNumber() {
        body.set("""
                {"model":"gpt-4.1-mini",
                 "choices":[{"message":{"content":"hi"}}],
                 "usage":{"prompt_tokens":"12","completion_tokens":3}}
                """);
        OpenAiChatBackend backend = new OpenAiChatBackend(baseUrl(), "k", "gpt-4.1-mini", Duration.ofSeconds(5));

        ChatResponse response = backend.chat(call());

        assertThat(response.text()).isEqualTo("hi");
        assertThat(response.usage().promptTokens()).isEqualTo(12);
    }

    /**
     * The one that matters. A misshapen response from the primary has to reach the fallback loop as
     * something it catches; an unwrapped {@code JsonNodeException} would walk straight past it.
     */
    @Test
    void aMisshapenPrimaryFailsOverToTheNextProvider() {
        body.set("""
                {"model":"gpt-4.1-mini",
                 "choices":[{"message":{"content":[{"type":"text","text":"hi"}]}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """);
        ChatBackend drifting = new OpenAiChatBackend(baseUrl(), "k", "gpt-4.1-mini", Duration.ofSeconds(5));
        ChatBackend healthy = new StubBackend();

        ChatResponse response = FallbackChatBackend.of(drifting, healthy).chat(call());

        assertThat(response.text()).isEqualTo("the second provider answered");
    }

    private static final class StubBackend implements ChatBackend {

        @Override
        public ChatResponse chat(ChatCall call) {
            return new ChatResponse("the second provider answered", List.of(), "stub", new LlmUsage(1, 1), "{}");
        }

        @Override
        public java.util.stream.Stream<String> stream(ChatCall call) {
            return java.util.stream.Stream.of("the second provider answered");
        }

        @Override
        public String defaultModel() {
            return "stub";
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }
}
