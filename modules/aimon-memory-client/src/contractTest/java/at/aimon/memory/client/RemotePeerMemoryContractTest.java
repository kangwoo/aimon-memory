package at.aimon.memory.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.PeerView;
import at.aimon.memory.testkit.AbstractPeerMemoryContractTest;

/**
 * {@link RemotePeerMemory} held to the same five-tier contract as every other {@code PeerMemory} backend.
 *
 * <p>
 * This is the reason aimon-core promoted {@code aimon-memory-testkit} to a published artifact. Until this class
 * existed the contract had exactly one subject — the store-backed default in aimon-core — so the question the suite
 * is written to answer, "do two backends mean the same thing by the same call", had only one answer to compare.
 * {@link RemotePeerMemoryWireTest} could not stand in for it: it asks whether the right bytes go out and the right
 * objects come back, which is a different question from whether the answer means what a caller two layers up will
 * read it as.
 *
 * <h2>Why a stub server rather than a deployment</h2>
 *
 * <p>
 * The same reason {@code RemotePeerMemoryWireTest} uses one, plus a build constraint. This module depends on
 * aimon-core and on nothing else in this build, deliberately (see its build file), so it cannot start the service —
 * that would drag pgvector, Flyway, Lucene and a Spring Boot application onto the classpath of the one module whose
 * whole point is not having them. A real deployment behind this suite belongs in the Testcontainers tier of a module
 * that already pays for it, not here.
 *
 * <h2>What the stub simplifies, and why that is the honest trade</h2>
 *
 * <p>
 * The stub keys conclusions by the <em>observed</em> peer alone, where the service keys them by the directed pair.
 * That is a deliberate simplification: the suite seeds through {@link #seedObservation} as {@code agent → alice} and
 * then searches with no observer, which the adapter correctly maps to alice's self-pair. Keyed strictly by pair, the
 * stub would answer every SEARCH case with an empty list and the tier would report green while asserting nothing —
 * ordering, topK and the score monotonicity all pass trivially on an empty list. Pair direction is not left
 * unchecked by this: it is asserted request-by-request in {@code RemotePeerMemoryWireTest}, which is the test that
 * can see the wire. Here the subject under test is the adapter's contract behaviour, and a stub that lets the
 * SEARCH cases actually run is worth more than one that reproduces a key it is not measuring.
 */
class RemotePeerMemoryContractTest extends AbstractPeerMemoryContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Conclusions the stub is holding, by observed peer. Reset per case, because the server is. */
    private final Map<String, List<ObjectNode>> conclusions = new LinkedHashMap<>();

    private final AtomicInteger nextId = new AtomicInteger();

    private HttpServer server;

    /**
     * {@inheritDoc}
     *
     * <p>
     * A fresh server on a fresh ephemeral port per case, which is what makes the backend empty: the state a remote
     * backend has to reset lives on the other side of the wire, so restarting the stub is this backend's equivalent
     * of truncating a table.
     */
    @Override
    protected PeerMemory newBackend() {
        conclusions.clear();
        nextId.set(0);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the stub AIMON Memory API", e);
        }
        server.createContext("/", this::handle);
        server.start();
        return new RemotePeerMemory(
                RemoteMemoryOptions.builder().baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                        .token("contract-token").agentPeer("agent").build());
    }

    /**
     * Stops the stub.
     *
     * <p>
     * Not folded into the suite's {@code resourceOwner()} hook: that one names the object holding the
     * <em>backend's</em> resources, and {@link RemotePeerMemory} is not {@link AutoCloseable} — the JDK HTTP client
     * it owns needs no closing. The server is this test's resource, not the backend's. JUnit runs a subclass
     * {@code @AfterEach} before the superclass's, so this happens while the backend is still around, which is the
     * order that matters if the adapter ever grows a close.
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Written straight into the stub's store rather than through the OBSERVE tier, as the suite asks: a backend may
     * serve SEARCH without serving OBSERVE, and going through the write tier would make the read cases depend on it.
     */
    @Override
    protected boolean seedObservation(PeerView subject, PeerView observer, String content) {
        store(subject.getPrincipal().getId(), observer.getPrincipal().getId(), content);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The same store. This adapter's snapshot reads the conclusions listing rather than a distilled peer card — see
     * {@code RemotePeerMemory#readSnapshot} for why — so for this backend "something the SNAPSHOT tier can read" and
     * "an observation" are the same row, and the session id is not part of it: a conclusion outlives the session it
     * was derived from.
     */
    @Override
    protected boolean seedSnapshot(PeerView subject, PeerView observer, String sessionId, String summary) {
        store(subject.getPrincipal().getId(), observer.getPrincipal().getId(), summary);
        return true;
    }

    private ObjectNode store(String observed, String observer, String content) {
        ObjectNode conclusion = MAPPER.createObjectNode();
        conclusion.put("id", "c" + nextId.incrementAndGet());
        conclusion.put("observer", observer);
        conclusion.put("observed", observed);
        conclusion.put("content", content);
        conclusion.put("level", "explicit");
        // Derived on the server from the level and the re-derivation count, never from a caller's number — which is
        // what `storesConfidence() == false` says out loud. A value is returned because the service returns one.
        conclusion.put("confidence", 0.7d);
        conclusion.put("timesDerived", 1);
        conclusion.put("createdAt", Instant.now().toString());
        conclusions.computeIfAbsent(observed, key -> new ArrayList<>()).add(conclusion);
        return conclusion;
    }

    // ── the stub API ────────────────────────────────────────────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        JsonNode body = requestBody.length == 0
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(new String(requestBody, StandardCharsets.UTF_8));

        String payload;
        if (path.endsWith("/recall")) {
            payload = recall(body);
        } else if (path.endsWith("/chat")) {
            payload = "{\"answer\":\"nothing has been concluded about that peer yet\",\"toolCalls\":[]}";
        } else if (path.endsWith("/messages")) {
            payload = "{\"items\":[]}";
        } else if (path.endsWith("/conclusions")) {
            payload = "POST".equals(exchange.getRequestMethod())
                    ? inject(body)
                    : listing(queryParam(exchange.getRequestURI().getQuery(), "observed"));
        } else {
            payload = "{}";
        }

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** {@code POST /v1/workspaces/{ws}/recall} — ranked, thresholded and capped, as the service does it. */
    private String recall(JsonNode body) {
        String observed = body.path("observed").asText("");
        String query = body.path("query").asText("");
        double threshold = body.path("threshold").asDouble(0.0d);
        int limit = body.path("limit").asInt(10);

        List<ObjectNode> matches = new ArrayList<>();
        for (ObjectNode conclusion : conclusions.getOrDefault(observed, List.of())) {
            double score = overlap(query, conclusion.path("content").asText(""));
            if (score <= 0.0d || score < threshold) {
                continue;
            }
            ObjectNode hit = MAPPER.createObjectNode();
            hit.set("conclusion", conclusion);
            hit.put("score", score);
            hit.set("explain", MAPPER.createObjectNode().put("sem", score).put("kw", score));
            matches.add(hit);
        }
        // Descending, because the adapter reports `ranksByScore() == true` and the contract then requires the scores
        // to be non-increasing along the list it hands back. A server that ranked and an adapter that reordered
        // would both look right in isolation.
        matches.sort(Comparator.comparingDouble((ObjectNode hit) -> hit.path("score").asDouble()).reversed());

        ArrayNode hits = MAPPER.createArrayNode();
        matches.stream().limit(limit).forEach(hits::add);
        return MAPPER.createObjectNode().set("hits", hits).toString();
    }

    /** {@code POST /v1/workspaces/{ws}/conclusions} — direct injection, answered with the stored row. */
    private String inject(JsonNode body) {
        return store(body.path("observed").asText(""), body.path("observer").asText(""),
                body.path("content").asText("")).toString();
    }

    /** {@code GET /v1/workspaces/{ws}/conclusions} — one page, and there is never a second one here. */
    private String listing(String observed) {
        ArrayNode items = MAPPER.createArrayNode();
        conclusions.getOrDefault(observed, List.of()).forEach(items::add);
        ObjectNode page = MAPPER.createObjectNode();
        page.set("items", items);
        page.put("hasNext", false);
        return page.toString();
    }

    /**
     * A crude term overlap standing in for the ranker's six signals.
     *
     * <p>
     * The number does not have to match the service's; what the contract cases read off it is that it is in
     * {@code [0, 1]}, that it separates a match from a miss, and that it orders. Reproducing pgvector distance here
     * would be a second ranker to keep in step with the real one, measuring nothing extra.
     */
    private static double overlap(String query, String content) {
        String haystack = content.toLowerCase(Locale.ROOT);
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        int hit = 0;
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) {
                hit++;
            }
        }
        return terms.length == 0 ? 0.0d : (double) hit / terms.length;
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && pair.substring(0, split).equals(name)) {
                return pair.substring(split + 1);
            }
        }
        return "";
    }
}
