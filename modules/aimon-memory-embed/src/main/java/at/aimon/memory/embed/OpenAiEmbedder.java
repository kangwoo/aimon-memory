package at.aimon.memory.embed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.text.TokenCounter;

/**
 * OpenAI embeddings over plain HTTP.
 *
 * <p>Four behaviours that matter more than they look:
 *
 * <ul>
 *   <li>{@code encoding_format: "float"} is sent explicitly. The default is base64 on some API
 *       versions, and a client that assumes float silently decodes garbage.
 *   <li>Over-long inputs are truncated, never dropped. Dropping one input shifts every later vector
 *       by one position and mislabels a whole batch of conclusions.
 *   <li>A failed batch retries as individual calls, so one bad input costs one embedding rather than
 *       ninety-six.
 *   <li>Results are re-ordered by the provider's {@code index} field. It is documented to come back
 *       in order; relying on that without checking is how order bugs survive to production.
 * </ul>
 */
public final class OpenAiEmbedder implements Embedder {

    private final EmbeddingProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiEmbedder(EmbeddingProperties props) {
        this(props, HttpClient.newBuilder().connectTimeout(props.timeout()).build());
    }

    public OpenAiEmbedder(EmbeddingProperties props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public float[] embed(String text, EmbedPurpose purpose) {
        return embedBatch(List.of(text), purpose).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += props.maxBatchSize()) {
            int end = Math.min(texts.size(), start + props.maxBatchSize());
            List<String> slice = texts.subList(start, end);
            out.addAll(embedSlice(slice));
        }
        return out;
    }

    private List<float[]> embedSlice(List<String> slice) {
        List<String> prepared = slice.stream().map(this::prepare).toList();
        try {
            return callBatch(prepared);
        } catch (EmbeddingException batchFailure) {
            if (prepared.size() == 1) {
                throw batchFailure;
            }
            // One poisonous input should not cost the whole slice. Fall back to per-input calls.
            List<float[]> out = new ArrayList<>(prepared.size());
            for (String text : prepared) {
                out.add(callBatch(List.of(text)).get(0));
            }
            return out;
        }
    }

    private String prepare(String text) {
        String safe = text == null || text.isBlank() ? " " : text;
        return TokenCounter.truncate(safe, props.maxInputTokens());
    }

    private List<float[]> callBatch(List<String> inputs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("encoding_format", "float");
        body.put("dimensions", props.dimensions());
        ArrayNode array = body.putArray("input");
        inputs.forEach(array::add);

        JsonNode response = post("/embeddings", body);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw new EmbeddingException(
                    "provider returned " + data.size() + " vectors for " + inputs.size() + " inputs");
        }
        float[][] ordered = new float[inputs.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= ordered.length) {
                throw new EmbeddingException("provider returned out-of-range index " + index);
            }
            ordered[index] = toVector(item.path("embedding"));
        }
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw new EmbeddingException("provider omitted a vector at index " + i);
            }
        }
        return List.of(ordered);
    }

    private float[] toVector(JsonNode node) {
        if (!node.isArray()) {
            throw new EmbeddingException("embedding is not an array");
        }
        if (node.size() != props.dimensions()) {
            throw new EmbeddingException("expected " + props.dimensions() + " dimensions, got " + node.size());
        }
        float[] vector = new float[node.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private JsonNode post(String path, ObjectNode body) {
        EmbeddingException last = null;
        for (int attempt = 0; attempt < props.maxAttempts(); attempt++) {
            if (attempt > 0) {
                Backoff.sleep(attempt);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl() + path))
                        .timeout(props.timeout()).header("Authorization", "Bearer " + props.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return mapper.readTree(response.body());
                }
                EmbeddingException failure = new EmbeddingException(
                        "embedding request failed: HTTP " + response.statusCode() + " " + truncate(response.body()));
                if (!isRetryable(response.statusCode())) {
                    throw failure;
                }
                last = failure;
            } catch (IOException e) {
                last = new EmbeddingException("embedding request failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("interrupted during embedding call", e);
            }
        }
        throw last == null ? new EmbeddingException("embedding request failed") : last;
    }

    private static boolean isRetryable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static String truncate(String body) {
        return body.length() <= 400 ? body : body.substring(0, 400) + "…";
    }

    @Override
    public int dimensions() {
        return props.dimensions();
    }
}
