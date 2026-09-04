package at.aimon.memory.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemoryIngestReceipt;
import at.aimon.core.memory.MemoryIngestRequest;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySnapshot;
import at.aimon.core.memory.MemorySnapshotQuery;
import at.aimon.core.memory.MemorySnapshotScope;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.dialectic.ReasoningLevel;

/**
 * What this adapter actually sends, and what it makes of what comes back.
 *
 * <p>
 * A real server on an ephemeral port rather than a mocked {@link java.net.http.HttpClient}, for the same reason
 * {@code ProviderWireTest} in aimon-memory-llm uses one: the thing under test is the request the AIMON Memory API
 * receives, and a mock that intercepts before serialisation asserts on the object graph instead.
 *
 * <p>
 * The direction of the pair is what most of this is about. An adapter that swaps observer and observed compiles,
 * runs, returns plausible results, and hands one peer another peer's memory — the single failure this whole system
 * is built to prevent, reintroduced in the translation layer.
 */
class RemotePeerMemoryWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Workspace WORKSPACE = Workspace.builder().id("ws").displayName("ws").build();
    private static final PeerView ALICE = PeerView.of(WORKSPACE, Principal.user("alice"));
    private static final PeerView BOB = PeerView.of(WORKSPACE, Principal.user("bob"));

    private HttpServer server;
    private RemotePeerMemory memory;

    /** Path to the request that arrived there, so a test can assert on the body the adapter sent. */
    private final Map<String, JsonNode> requests = new ConcurrentHashMap<>();

    private final Map<String, String> queries = new ConcurrentHashMap<>();

    /** Path to the canned response, set per test. */
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    private final List<String> authorizations = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        memory = new RemotePeerMemory(
                RemoteMemoryOptions.builder().baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                        .token("test-token").agentPeer("assistant").build());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (exchange.getRequestURI().getQuery() != null) {
            queries.put(path, exchange.getRequestURI().getQuery());
        }
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        if (requestBody.length > 0) {
            requests.put(path, MAPPER.readTree(new String(requestBody, StandardCharsets.UTF_8)));
        }
        int status = statuses.getOrDefault(path, 200);
        byte[] payload = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    // ── capability model ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void offersAllFiveTiers() {
        assertThat(MemoryCapabilities.of(memory)).containsExactlyInAnyOrder(MemoryCapability.SNAPSHOT,
                MemoryCapability.SEARCH, MemoryCapability.CHAT, MemoryCapability.OBSERVE, MemoryCapability.INGEST);
    }

    /**
     * {@code PeerMemory} requires the accessors to be constant and free of side effects, because
     * {@code MemoryCapabilities.of} calls all five on every invocation — from assembly, from validation and from two
     * toString()s. An adapter that answered them by asking its server would turn one log line into five round trips.
     */
    @Test
    void theAccessorsAreFieldReadsAndTalkToNobody() {
        for (int i = 0; i < 5; i++) {
            assertThat(MemoryCapabilities.of(memory)).isEqualTo(MemoryCapabilities.of(memory));
        }
        assertThat(memory.searcher()).containsSame(memory.searcher().orElseThrow());
        assertThat(authorizations).as("no request was made while asking what the backend can do").isEmpty();
    }

    @Test
    void theHonestSignalsAreFalse() {
        assertThat(memory.searcher().orElseThrow().ranksByScore()).isTrue();
        assertThat(memory.searcher().orElseThrow().narrowsBySession())
                .as("recall ranks a pair's conclusions, which outlive the session that produced them").isFalse();
        assertThat(memory.observationRecorder().orElseThrow().storesConfidence())
                .as("confidence is derived from level and reinforcement, not supplied by the caller").isFalse();
    }

    @Test
    void everyRequestCarriesTheBearerToken() {
        responses.put("/v1/workspaces/ws/recall", "{\"hits\":[]}");
        memory.searcher().orElseThrow().search(MemorySearchQuery.builder().subject(ALICE).query("tea").build());
        assertThat(authorizations).containsExactly("Bearer test-token");
    }

    // ── SEARCH ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void searchSendsThePairInTheDirectionTheServiceExpects() {
        responses.put("/v1/workspaces/ws/recall", "{\"hits\":[]}");

        memory.searcher().orElseThrow().search(
                MemorySearchQuery.builder().subject(ALICE).observer(BOB).query("tea").topK(7).minScore(0.25).build());

        JsonNode sent = requests.get("/v1/workspaces/ws/recall");
        assertThat(sent.path("observed").asText()).as("the subject is who the memory is about").isEqualTo("alice");
        assertThat(sent.path("observer").asText()).as("the observer is whose memory it is").isEqualTo("bob");
        assertThat(sent.path("query").asText()).isEqualTo("tea");
        assertThat(sent.path("limit").asInt()).isEqualTo(7);
        assertThat(sent.path("threshold").asDouble()).isEqualTo(0.25);
        assertThat(sent.path("explain").asBoolean()).as("the signal breakdown is asked for, not optional").isTrue();
    }

    /** With no observer, the pair is the subject's own — what alice concluded about herself. */
    @Test
    void searchWithNoObserverAsksTheSelfPair() {
        responses.put("/v1/workspaces/ws/recall", "{\"hits\":[]}");
        memory.searcher().orElseThrow().search(MemorySearchQuery.builder().subject(ALICE).query("tea").build());

        JsonNode sent = requests.get("/v1/workspaces/ws/recall");
        assertThat(sent.path("observer").asText()).isEqualTo("alice");
        assertThat(sent.path("observed").asText()).isEqualTo("alice");
    }

    @Test
    void searchCarriesTheSixSignalsThroughAsHitSignals() {
        responses.put("/v1/workspaces/ws/recall", """
                {"hits":[{"conclusion":{"id":"c1","observer":"bob","observed":"alice","content":"alice drinks tea",
                          "level":"explicit","confidence":0.82,"messageIds":[1,2],"timesDerived":3,
                          "createdAt":"2026-01-01T00:00:00Z"},
                          "score":0.71,
                          "explain":{"sem":0.6,"kw":0.9,"ent":0.1,"reinf":0.3,"rec":0.5,"lvl":1.0}}]}
                """);

        List<MemoryHit> hits = memory.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(ALICE).observer(BOB).query("tea").build());

        assertThat(hits).hasSize(1);
        MemoryHit hit = hits.get(0);
        assertThat(hit.getScore()).isEqualTo(0.71);
        assertThat(hit.getSignals()).containsExactlyInAnyOrderEntriesOf(
                Map.of("sem", 0.6, "kw", 0.9, "ent", 0.1, "reinf", 0.3, "rec", 0.5, "lvl", 1.0));
        Observation observation = hit.getObservation();
        assertThat(observation.getContent()).isEqualTo("alice drinks tea");
        assertThat(observation.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(observation.getSubject().getPrincipal().getId()).isEqualTo("alice");
        assertThat(observation.getObserver().getPrincipal().getId()).isEqualTo("bob");
        assertThat(observation.getConfidence()).isEqualTo(0.82);
        assertThat(observation.getMetadata()).containsEntry("timesDerived", "3");
    }

    /**
     * A weight change on the server that pushed a fused score a hair past 1.0 would otherwise throw inside
     * MemoryHit's builder and take the whole search down, rather than return a slightly odd number.
     */
    @Test
    void aScoreOutsideTheUnitRangeIsClampedRatherThanThrown() {
        responses.put("/v1/workspaces/ws/recall", """
                {"hits":[{"conclusion":{"id":"c1","observed":"alice","content":"x","level":"explicit"},
                          "score":1.0000001}]}
                """);
        List<MemoryHit> hits = memory.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(ALICE).query("tea").build());
        assertThat(hits).singleElement().extracting(MemoryHit::getScore).isEqualTo(1.0);
    }

    // ── SNAPSHOT ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSnapshotRendersTheConclusionsAndKeepsThemAsObservations() {
        responses.put("/v1/workspaces/ws/conclusions", """
                {"items":[{"id":"c1","observer":"bob","observed":"alice","content":"alice drinks tea",
                           "level":"explicit","createdAt":"2026-01-01T00:00:00Z"},
                          {"id":"c2","observer":"bob","observed":"alice","content":"alice lives in busan",
                           "level":"deductive","createdAt":"2026-01-02T00:00:00Z"}],
                 "page":0,"size":50,"total":2,"hasNext":false}
                """);

        MemorySnapshot snapshot = memory.snapshotReader().orElseThrow().read(MemorySnapshotQuery.builder()
                .subject(ALICE).observer(BOB).scope(MemorySnapshotScope.LOCAL).mode(MemoryInjectionMode.FULL).build())
                .orElseThrow();

        assertThat(snapshot.getRenderedText()).isEqualTo("alice drinks tea\nalice lives in busan");
        assertThat(snapshot.getResolvedScope()).isEqualTo(MemorySnapshotScope.LOCAL);
        assertThat(snapshot.isObservationsAvailable()).isTrue();
        assertThat(snapshot.getObservations()).extracting(Observation::getContent).containsExactly("alice drinks tea",
                "alice lives in busan");
        assertThat(snapshot.isTokenCountEstimated()).as("the model's tokenizer is on the other side of this boundary")
                .isTrue();
        assertThat(queries.get("/v1/workspaces/ws/conclusions")).contains("observer=bob").contains("observed=alice");
    }

    /** A peer with nothing recorded is an answer, not a failure — and so is a workspace the token cannot see. */
    @Test
    void anEmptyPairIsAnEmptyOptionalRatherThanAnException() {
        responses.put("/v1/workspaces/ws/conclusions", "{\"items\":[],\"hasNext\":false}");
        assertThat(memory.snapshotReader().orElseThrow()
                .read(MemorySnapshotQuery.builder().subject(ALICE).scope(MemorySnapshotScope.GLOBAL).build()))
                .isEmpty();
    }

    /**
     * LOCAL_THEN_GLOBAL is the only scope that can cost two calls, and the snapshot has to report which one
     * answered — the resolved scope is a fact about the result, not a repetition of the request.
     */
    @Test
    void localThenGlobalFallsBackToTheSelfPairAndSaysSo() throws IOException {
        List<String> observers = new ArrayList<>();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            observers.add(query);
            boolean selfPair = query.contains("observer=alice");
            byte[] payload = (selfPair
                    ? "{\"items\":[{\"id\":\"c9\",\"observed\":\"alice\",\"content\":\"alice drinks tea\","
                            + "\"level\":\"explicit\"}],\"hasNext\":false}"
                    : "{\"items\":[],\"hasNext\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        MemorySnapshot snapshot = memory.snapshotReader().orElseThrow().read(MemorySnapshotQuery.builder()
                .subject(ALICE).observer(BOB).scope(MemorySnapshotScope.LOCAL_THEN_GLOBAL).build()).orElseThrow();

        assertThat(observers).hasSize(2);
        assertThat(observers.get(0)).as("bob's view of alice is asked for first").contains("observer=bob");
        assertThat(observers.get(1)).as("then alice's own").contains("observer=alice");
        assertThat(snapshot.getResolvedScope()).isEqualTo(MemorySnapshotScope.GLOBAL);
    }

    @Test
    void aTokenBudgetTrimsWholeFactsAndFlagsTheSnapshotTruncated() {
        responses.put("/v1/workspaces/ws/conclusions", """
                {"items":[{"id":"c1","observed":"alice","content":"0123456789012345678901234567890123456789",
                           "level":"explicit"},
                          {"id":"c2","observed":"alice","content":"this one does not fit","level":"explicit"}],
                 "hasNext":false}
                """);

        MemorySnapshot snapshot = memory.snapshotReader().orElseThrow().read(
                MemorySnapshotQuery.builder().subject(ALICE).scope(MemorySnapshotScope.GLOBAL).maxTokens(12).build())
                .orElseThrow();

        assertThat(snapshot.getRenderedText()).isEqualTo("0123456789012345678901234567890123456789");
        assertThat(snapshot.isTruncated()).isTrue();
        assertThat(snapshot.getObservations()).hasSize(1);
    }

    // ── OBSERVE ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void observeSendsThePairAndTheContent() {
        responses.put("/v1/workspaces/ws/conclusions", """
                {"id":"c5","observer":"bob","observed":"alice","content":"alice drinks tea","level":"explicit",
                 "createdAt":"2026-01-01T00:00:00Z"}
                """);

        Observation stored = memory.observationRecorder().orElseThrow()
                .observe(ObservationDraft.builder().subject(ALICE).observer(BOB).sessionId("s1")
                        .content("alice drinks tea").type(ObservationType.EXPLICIT).build());

        JsonNode sent = requests.get("/v1/workspaces/ws/conclusions");
        assertThat(sent.path("observer").asText()).isEqualTo("bob");
        assertThat(sent.path("observed").asText()).isEqualTo("alice");
        assertThat(sent.path("content").asText()).isEqualTo("alice drinks tea");
        assertThat(sent.path("session").asText()).isEqualTo("s1");
        assertThat(stored.getId().getLocalId()).isEqualTo("c5");
    }

    // ── INGEST ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The service stores a speaker per message because a pair is directed. aimon-core's request carries one observer
     * and roles instead, so this mapping is the whole of the translation and getting it wrong files the assistant's
     * turns as things the user said.
     */
    @Test
    void ingestNamesASpeakerPerMessageFromTheRole() {
        responses.put("/v1/workspaces/ws/sessions/s1/messages", "{\"items\":[]}");

        MemoryIngestReceipt receipt = memory.ingestor().orElseThrow()
                .ingest(MemoryIngestRequest.builder().observer(ALICE).sessionId("s1")
                        .messages(List.of(Message.user("I drink tea"), Message.assistant("noted"))).build());

        JsonNode sent = requests.get("/v1/workspaces/ws/sessions/s1/messages");
        assertThat(sent.path("messages")).hasSize(2);
        assertThat(sent.path("messages").get(0).path("peer").asText()).isEqualTo("alice");
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("I drink tea");
        assertThat(sent.path("messages").get(1).path("peer").asText()).isEqualTo("assistant");
        assertThat(receipt.getAccepted()).isEqualTo(2);
        assertThat(receipt.isDerived()).as("ingestion queues; the worker derives afterwards").isFalse();
    }

    @Test
    void anIngestWithNothingToSayMakesNoRequest() {
        MemoryIngestReceipt receipt = memory.ingestor().orElseThrow().ingest(MemoryIngestRequest.builder()
                .observer(ALICE).sessionId("s1").messages(List.of(Message.user("   "))).build());

        assertThat(receipt.getAccepted()).isZero();
        assertThat(authorizations).as("a request with no messages is not worth a round trip").isEmpty();
    }

    // ── CHAT ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void chatSendsThePairTheSessionAndAMappedReasoningLevel() {
        responses.put("/v1/workspaces/ws/chat",
                "{\"answer\":\"she drinks tea\",\"iterations\":2,\"stoppedAtLimit\":false,\"toolCalls\":[]}");

        DialecticResponse response = memory.dialecticEngine().orElseThrow()
                .query(DialecticQuery.builder().workspace(WORKSPACE).subject(ALICE).observer(BOB).sessionId("s1")
                        .question("what does alice drink?").level(ReasoningLevel.DEEP).build());

        JsonNode sent = requests.get("/v1/workspaces/ws/chat");
        assertThat(sent.path("observer").asText()).isEqualTo("bob");
        assertThat(sent.path("observed").asText()).isEqualTo("alice");
        assertThat(sent.path("session").asText()).isEqualTo("s1");
        assertThat(sent.path("reasoningLevel").asText()).isEqualTo("high");
        assertThat(response.getAnswer()).isEqualTo("she drinks tea");
        assertThat(response.getObservationsConsidered())
                .as("the service reports tool calls, not the rows they returned").isEmpty();
    }

    // ── failures ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The service's own error code survives the trip. It is what separates "this deployment needs a model key" from
     * "this adapter got the pair wrong", and both arrive here as a non-2xx.
     */
    @Test
    void aRefusalKeepsTheStatusAndTheServicesErrorCode() {
        statuses.put("/v1/workspaces/ws/recall", 403);
        responses.put("/v1/workspaces/ws/recall",
                "{\"code\":\"forbidden\",\"message\":\"token is not scoped to observer bob\"}");

        assertThatThrownBy(() -> memory.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(ALICE).observer(BOB).query("tea").build()))
                .isInstanceOf(RemoteMemoryException.class).hasMessageContaining("token is not scoped to observer bob")
                .extracting(e -> ((RemoteMemoryException) e).getCode()).isEqualTo("forbidden");
    }

    @Test
    void anUnreachableServiceIsAnExceptionRatherThanAnEmptyAnswer() {
        server.stop(0);
        assertThatThrownBy(() -> memory.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(ALICE).query("tea").build()))
                .isInstanceOf(RemoteMemoryException.class).hasMessageContaining("could not reach");
    }

    /** The contract every offered tier owes: it answers, rather than refusing what its presence advertised. */
    @Test
    void noOfferedTierRefuses() {
        responses.put("/v1/workspaces/ws/conclusions", "{\"items\":[],\"hasNext\":false}");
        responses.put("/v1/workspaces/ws/recall", "{\"hits\":[]}");
        responses.put("/v1/workspaces/ws/chat", "{\"answer\":\"\"}");
        responses.put("/v1/workspaces/ws/sessions/s1/messages", "{}");

        assertThatCode(() -> {
            memory.snapshotReader().orElseThrow().read(MemorySnapshotQuery.builder().subject(ALICE).build());
            memory.searcher().orElseThrow().search(MemorySearchQuery.builder().subject(ALICE).query("tea").build());
            memory.dialecticEngine().orElseThrow().query(DialecticQuery.builder().workspace(WORKSPACE).subject(ALICE)
                    .observer(BOB).question("what?").build());
            memory.ingestor().orElseThrow().ingest(MemoryIngestRequest.builder().observer(ALICE).sessionId("s1")
                    .messages(List.of(Message.user("hello"))).build());
        }).doesNotThrowAnyException();
    }
}
