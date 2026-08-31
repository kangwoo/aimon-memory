package dev.dyad.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A real HTTP server on an ephemeral port.
 *
 * <p>Preferred over stubbing {@link java.net.http.HttpClient}: the behaviours under test — batching,
 * status handling, retry — live in the request and response handling, and a stubbed client would
 * bypass exactly the code that matters.
 */
final class FakeEmbeddingServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** @param responder receives the call index (from 1) and the request body */
    FakeEmbeddingServer(Function<Response.Request, Response> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/embeddings",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode parsed = MAPPER.readTree(body);
                    synchronized (requests) {
                        requests.add(parsed);
                    }
                    Response response = responder.apply(new Response.Request(calls.incrementAndGet(), parsed));
                    byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.status(), payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<JsonNode> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    int callCount() {
        return calls.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Response(int status, String body) {

        record Request(int index, JsonNode body) {

            int inputCount() {
                return body.path("input").size();
            }

            String input(int index) {
                return body.path("input").get(index).asText();
            }
        }

        /** Vectors whose first component encodes the input's position, so order can be asserted. */
        static Response vectorsFor(Request request, int dimensions, boolean reversed) {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode data = root.putArray("data");
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < request.inputCount(); i++) {
                order.add(i);
            }
            if (reversed) {
                java.util.Collections.reverse(order);
            }
            for (int position : order) {
                ObjectNode item = data.addObject();
                item.put("index", position);
                ArrayNode vector = item.putArray("embedding");
                for (int d = 0; d < dimensions; d++) {
                    vector.add(d == 0 ? (double) position : 0.0);
                }
            }
            return new Response(200, root.toString());
        }

        static Response error(int status, String message) {
            return new Response(status, "{\"error\":{\"message\":\"" + message + "\"}}");
        }
    }
}
