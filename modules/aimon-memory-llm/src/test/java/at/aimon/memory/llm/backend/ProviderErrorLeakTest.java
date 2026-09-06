package at.aimon.memory.llm.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import at.aimon.memory.llm.Json;
import at.aimon.memory.llm.LlmException;

/**
 * What a provider says stays out of the exception message.
 *
 * <p>{@code LlmException} is a {@code MemoryException}, so its message is copied verbatim into the
 * 500 body by {@code ApiExceptionHandler}. That made every one of these a channel for text the caller
 * never sent: an OpenAI 401 quotes the configured API key back with only its middle masked, a gateway
 * in front of the provider answers in HTML naming internal hosts, and a model's own output is written
 * from a prompt this build assembles out of the workspace's stored conclusions and messages.
 *
 * <p>A real server on an ephemeral port, for the same reason {@link ProviderWireTest} uses one: the
 * behaviour under test is the handling of a response, and a stubbed {@code HttpClient} would bypass
 * it.
 */
class ProviderErrorLeakTest {

    /** Shaped like the real thing: OpenAI's 401 body echoes the key it rejected. */
    private static final String LEAKY_401 = """
            {"error":{"message":"Incorrect API key provided: sk-proj-abc123SECRETxyz. You can find your\
             API key at https://platform.openai.com/account/api-keys","type":"invalid_request_error"}}""";

    private static final String SECRET = "sk-proj-abc123SECRETxyz";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ChatCall call() {
        return new ChatCall(null, "system", List.of(new ChatTurn.UserText("hi")), List.of(), null, 0.0, null);
    }

    @Test
    void aProviderErrorBodyIsNotCopiedIntoTheMessage() throws IOException {
        String url = serve(401, LEAKY_401);
        var backend = new OpenAiChatBackend(url, "k", "gpt-5", Duration.ofSeconds(5));

        LlmException thrown = (LlmException) catchThrowable(() -> backend.chat(call()));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo("llm_auth");
        assertThat(thrown.getMessage()).doesNotContain(SECRET).doesNotContain("platform.openai.com")
                .isEqualTo("openai returned HTTP 401");
    }

    /**
     * The status code is what a caller can act on, so it survives. Dropping it too would leave
     * {@code llm_retryable} as the only signal that the provider — rather than the request — is the
     * problem.
     */
    @Test
    void theStatusCodeStillReachesTheCaller() throws IOException {
        String url = serve(503, "{\"error\":{\"message\":\"upstream host db-7.internal is down\"}}");
        var backend = new AnthropicChatBackend(url, "k", "claude-opus-5", Duration.ofSeconds(5));

        LlmException thrown = (LlmException) catchThrowable(() -> backend.chat(call()));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo("llm_retryable");
        assertThat(thrown.getMessage()).contains("503").doesNotContain("db-7.internal");
    }

    /**
     * A 2xx that is not JSON — a proxy's HTML error page is the usual way — took the other route out
     * of {@code HttpSupport}, through {@code Json.read}, and quoted 200 characters of that page back.
     */
    @Test
    void aNonJsonBodyBehindATwoHundredIsNotQuotedBack() throws IOException {
        String url = serve(200, "<html><body>gateway timeout at edge-proxy-3.internal</body></html>");
        var backend = new OpenAiChatBackend(url, "k", "gpt-5", Duration.ofSeconds(5));

        assertThatThrownBy(() -> backend.chat(call())).isInstanceOf(LlmException.class)
                .hasMessageNotContaining("edge-proxy-3.internal").hasMessageNotContaining("<html>");
    }

    /**
     * The model's own answer is the case that carries stored memory: it is written from a prompt built
     * out of the workspace's conclusions and messages, so echoing it into a {@code bad_json} 500 hands
     * back memory the request never mentioned.
     */
    @Test
    void aModelAnswerThatWillNotParseIsNotQuotedBack() {
        String answer = "{\"fact\": \"alice's home address is 12 Rosengasse\" TRUNCATED";

        assertThatThrownBy(() -> Json.read(answer, Object.class)).isInstanceOf(LlmException.class)
                .hasMessageNotContaining("Rosengasse").hasMessageNotContaining("alice")
                .hasMessage("could not parse JSON as Object; see the server log");
    }

    /**
     * The mid-stream error event, which reaches the caller through a different path: the stream has
     * already been handed back, so this throws while it is being consumed rather than at the call.
     */
    @Test
    void aMidStreamProviderErrorKeepsItsTypeButNotItsProse() throws IOException {
        String url = serve(200, """
                data: {"type":"error","error":{"type":"overloaded_error",\
                "message":"tenant acme-corp exceeded its quota"}}

                """);
        var backend = new AnthropicChatBackend(url, "k", "claude-opus-5", Duration.ofSeconds(5));

        Supplier<List<String>> drain = () -> backend.stream(call()).toList();

        LlmException thrown = (LlmException) catchThrowable(drain::get);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo("llm_stream_error");
        assertThat(thrown.getMessage()).contains("overloaded_error").doesNotContain("acme-corp")
                .doesNotContain("quota");
    }

    /**
     * The same event in OpenAI's shape. Written separately rather than parameterised because the two
     * backends parse it independently — {@code error} as a sibling of {@code choices} here, an
     * {@code error} event type there — so one test passing says nothing about the other.
     */
    @Test
    void theOpenAiMidStreamErrorAlsoKeepsOnlyItsType() throws IOException {
        String url = serve(200, """
                data: {"error":{"type":"server_error",\
                "message":"tenant acme-corp exceeded its quota"}}

                data: [DONE]

                """);
        var backend = new OpenAiChatBackend(url, "k", "gpt-5", Duration.ofSeconds(5));

        Supplier<List<String>> drain = () -> backend.stream(call()).toList();

        LlmException thrown = (LlmException) catchThrowable(drain::get);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo("llm_stream_error");
        assertThat(thrown.getMessage()).contains("server_error").doesNotContain("acme-corp").doesNotContain("quota");
    }
}
