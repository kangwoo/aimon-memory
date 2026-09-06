package at.aimon.memory.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.spi.EmbedPurpose;

class OpenAiEmbedderTest {

    private static final int DIMENSIONS = 8;

    private static EmbeddingProperties properties(String baseUrl, int maxBatchSize, int maxAttempts) {
        return new EmbeddingProperties(baseUrl, "test-key", "text-embedding-3-small", DIMENSIONS, maxBatchSize, 50,
                maxAttempts, Duration.ofSeconds(5));
    }

    private static List<String> inputs(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add("input " + i);
        }
        return out;
    }

    /**
     * {@code encoding_format} is sent explicitly because the API default has not always been float,
     * and a client that assumes float while receiving base64 decodes noise without failing.
     */
    @Test
    void sendsAnExplicitFloatEncodingAndDimensionCount() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embed("hello", EmbedPurpose.DOCUMENT);

            var body = server.requests().get(0);
            assertThat(body.path("encoding_format").asText()).isEqualTo("float");
            assertThat(body.path("dimensions").asInt()).isEqualTo(DIMENSIONS);
            assertThat(body.path("model").asText()).isEqualTo("text-embedding-3-small");
        }
    }

    @Test
    void splitsOversizedBatchesIntoSeveralRequests() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            var vectors = new OpenAiEmbedder(properties(server.baseUrl(), 10, 1)).embedBatch(inputs(25),
                    EmbedPurpose.DOCUMENT);

            assertThat(vectors).hasSize(25);
            assertThat(server.callCount()).isEqualTo(3);
            assertThat(server.requests()).extracting(r -> r.path("input").size()).containsExactly(10, 10, 5);
        }
    }

    /**
     * The provider documents that results come back in order. Relying on that without checking is how
     * an ordering bug survives to production, and a misaligned batch labels every conclusion in it
     * with the wrong vector.
     */
    @Test
    void reordersByTheProvidersIndexField() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, true))) {
            var vectors = new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embedBatch(inputs(5),
                    EmbedPurpose.DOCUMENT);

            for (int i = 0; i < 5; i++) {
                assertThat(vectors.get(i)[0]).as("position %d", i).isEqualTo((float) i);
            }
        }
    }

    /** Over-long input is truncated, never dropped: dropping one shifts every later vector by one. */
    @Test
    void truncatesRatherThanDroppingOverlongInput() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            String enormous = "word ".repeat(500);
            var vectors = new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embedBatch(List.of("short", enormous),
                    EmbedPurpose.DOCUMENT);

            assertThat(vectors).hasSize(2);
            assertThat(server.requests().get(0).path("input").get(1).asText()).hasSizeLessThan(enormous.length());
        }
    }

    @Test
    void blankInputBecomesSomethingTheProviderAccepts() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embedBatch(List.of("", "   "),
                    EmbedPurpose.DOCUMENT);

            assertThat(server.requests().get(0).path("input").get(0).asText()).isNotEmpty();
        }
    }

    @Test
    void retriesTransientFailuresAndSucceeds() throws IOException {
        try (var server = new FakeEmbeddingServer(request -> request.index() < 3
                ? FakeEmbeddingServer.Response.error(429, "rate limited")
                : FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            var vectors = new OpenAiEmbedder(properties(server.baseUrl(), 96, 4)).embedBatch(List.of("hello"),
                    EmbedPurpose.DOCUMENT);

            assertThat(vectors).hasSize(1);
            assertThat(server.callCount()).isEqualTo(3);
        }
    }

    /** A 400 will be a 400 next time too. Retrying it wastes the budget for a real transient failure. */
    @Test
    void doesNotRetryARejectedRequest() throws IOException {
        try (var server = new FakeEmbeddingServer(request -> FakeEmbeddingServer.Response.error(400, "bad model"))) {
            assertThatThrownBy(
                    () -> new OpenAiEmbedder(properties(server.baseUrl(), 96, 4)).embed("hello", EmbedPurpose.DOCUMENT))
                    .isInstanceOf(EmbeddingException.class).hasMessageContaining("HTTP 400");

            assertThat(server.callCount()).isEqualTo(1);
        }
    }

    /**
     * The provider's error body stays out of the message.
     *
     * <p>{@code EmbeddingException} is a {@code MemoryException}, so its message is copied verbatim
     * into the 500 body. The embedding provider's body is not the caller's text — an OpenAI 401 quotes
     * the configured key back with only its middle masked — and it was being appended to the status
     * code. The status code itself stays: it is what tells a caller whether to retry.
     */
    @Test
    void doesNotCopyTheProviderErrorBodyIntoTheMessage() throws IOException {
        String secret = "sk-proj-abc123SECRETxyz";
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.error(401, "Incorrect API key provided: " + secret))) {
            assertThatThrownBy(
                    () -> new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embed("hello", EmbedPurpose.DOCUMENT))
                    .isInstanceOf(EmbeddingException.class).hasMessageNotContaining(secret)
                    .hasMessage("embedding request failed: HTTP 401");
        }
    }

    /** One poisonous input should cost one embedding, not the whole slice of ninety-six. */
    @Test
    void fallsBackToIndividualCallsWhenABatchFails() throws IOException {
        try (var server = new FakeEmbeddingServer(request -> request.inputCount() > 1
                ? FakeEmbeddingServer.Response.error(400, "one of these is bad")
                : FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {

            var vectors = new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embedBatch(inputs(3),
                    EmbedPurpose.DOCUMENT);

            assertThat(vectors).hasSize(3);
            // One failed batch, then three individual calls.
            assertThat(server.callCount()).isEqualTo(4);
        }
    }

    @Test
    void rejectsAResponseWithTheWrongShape() throws IOException {
        try (var server = new FakeEmbeddingServer(request -> new FakeEmbeddingServer.Response(200,
                "{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}"))) {
            assertThatThrownBy(
                    () -> new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embed("hello", EmbedPurpose.DOCUMENT))
                    .isInstanceOf(EmbeddingException.class).hasMessageContaining("dimensions");
        }
    }

    @Test
    void anEmptyBatchDoesNotCallTheProvider() throws IOException {
        try (var server = new FakeEmbeddingServer(
                request -> FakeEmbeddingServer.Response.vectorsFor(request, DIMENSIONS, false))) {
            assertThat(new OpenAiEmbedder(properties(server.baseUrl(), 96, 1)).embedBatch(List.of(),
                    EmbedPurpose.DOCUMENT)).isEmpty();
            assertThat(server.callCount()).isZero();
        }
    }
}
