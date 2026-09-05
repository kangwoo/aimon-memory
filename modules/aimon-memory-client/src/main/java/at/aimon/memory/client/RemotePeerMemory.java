package at.aimon.memory.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemoryIngestReceipt;
import at.aimon.core.memory.MemoryIngestRequest;
import at.aimon.core.memory.MemoryIngestor;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.MemorySnapshot;
import at.aimon.core.memory.MemorySnapshotQuery;
import at.aimon.core.memory.MemorySnapshotReader;
import at.aimon.core.memory.MemorySnapshotScope;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.dialectic.ReasoningLevel;

/**
 * An AIMON Memory deployment, reached over HTTP, presented as the five tiers aimon-core replaces a memory backend at.
 *
 * <p>
 * {@link PeerMemory}'s own comment anticipates this: it sits at service altitude, "five operations that the
 * store-backed default and a remote memory service both have a name for". This is that remote service, and the fit is
 * close enough to be worth stating exactly, because where it is <em>not</em> close is what a caller has to know.
 *
 * <table border="1">
 * <caption>Tier to endpoint</caption>
 * <tr><th>Tier</th><th>Endpoint</th></tr>
 * <tr><td>SNAPSHOT</td><td>{@code GET /v1/workspaces/{ws}/conclusions}</td></tr>
 * <tr><td>SEARCH</td><td>{@code POST /v1/workspaces/{ws}/recall}</td></tr>
 * <tr><td>CHAT</td><td>{@code POST /v1/workspaces/{ws}/chat}</td></tr>
 * <tr><td>OBSERVE</td><td>{@code POST /v1/workspaces/{ws}/conclusions}</td></tr>
 * <tr><td>INGEST</td><td>{@code POST /v1/workspaces/{ws}/sessions/{session}/messages}</td></tr>
 * </table>
 *
 * <h2>Subject and observer are a pair</h2>
 *
 * <p>
 * AIMON Memory stores memory under a directed pair — what {@code bob} concluded about {@code alice} is a different
 * row from what {@code alice} concluded about herself, deliberately and all the way down to the composite key. That
 * is exactly aimon-core's subject/observer, so the mapping is the identity one: observed is the subject, observer is
 * the observer, and a query with no observer names the subject's own self-pair.
 *
 * <p>
 * The workspace comes off the subject's {@link PeerView}, and the peer name off its {@link Principal#getId()}. This
 * class is application-scoped and never remembers a workspace, as {@link PeerMemory} requires.
 *
 * <h2>What this backend does not have, and says so</h2>
 *
 * <p>
 * Three capability signals are false here, and each one is a real difference rather than an omission:
 *
 * <ul>
 * <li>{@link MemorySearcher#narrowsBySession()} — recall ranks a pair's conclusions, and a conclusion outlives the
 * session that produced it. There is no session parameter to pass, so a query carrying a session id is rejected
 * rather than answered across every session; the CHAT tier is the one that takes a session.</li>
 * <li>{@link ObservationRecorder#storesConfidence()} — an injected conclusion is stored at its level, and confidence
 * is derived from the level and from how often the fact has been re-derived. A caller's number has nowhere to go.</li>
 * <li>{@link MemoryIngestReceipt#isDerived()} — ingestion is a queue write. Derivation happens in the worker
 * afterwards, so this is always false, including when {@code waitForDerivation} was asked for.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * <p>
 * Every accessor on this class is a field read, as {@link PeerMemory} requires: the five tiers are built once in the
 * constructor. Nothing here asks the server what it can do — a capability query on a path whose author reasonably
 * assumed a field read is how an adapter turns one log line into five round trips.
 */
public final class RemotePeerMemory implements PeerMemory {

    /**
     * How many conclusions a snapshot reads before it starts trimming.
     *
     * <p>
     * The API pages at 50 by default and the snapshot is prompt material with a token budget, so a page is already
     * more than most budgets will hold. Reading more would spend a bigger query to throw the tail away.
     */
    private static final int SNAPSHOT_PAGE_SIZE = 50;

    /**
     * Characters per token, for the estimate {@link MemorySnapshot#isTokenCountEstimated()} exists to flag.
     *
     * <p>
     * The real count needs the model's tokenizer, which lives on the other side of this boundary. Four is the usual
     * English approximation and it is wrong for Korean in the direction that matters least — it over-counts, so a
     * budget is respected rather than overrun.
     */
    private static final int CHARS_PER_TOKEN = 4;

    private final MemoryHttp http;
    private final RemoteMemoryOptions options;
    private final MemorySnapshotReader snapshotReader;
    private final MemorySearcher searcher;
    private final DialecticEngine dialecticEngine;
    private final ObservationRecorder observationRecorder;
    private final MemoryIngestor ingestor;

    public RemotePeerMemory(RemoteMemoryOptions options) {
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.http = new MemoryHttp(options);
        this.snapshotReader = this::readSnapshot;
        this.searcher = new RemoteSearcher();
        this.dialecticEngine = this::chat;
        this.observationRecorder = new RemoteRecorder();
        this.ingestor = this::ingest;
    }

    @Override
    public String backendId() {
        return options.getBackendId();
    }

    @Override
    public Optional<MemorySnapshotReader> snapshotReader() {
        return Optional.of(snapshotReader);
    }

    @Override
    public Optional<MemorySearcher> searcher() {
        return Optional.of(searcher);
    }

    @Override
    public Optional<DialecticEngine> dialecticEngine() {
        return Optional.of(dialecticEngine);
    }

    @Override
    public Optional<ObservationRecorder> observationRecorder() {
        return Optional.of(observationRecorder);
    }

    @Override
    public Optional<MemoryIngestor> ingestor() {
        return Optional.of(ingestor);
    }

    // ── SNAPSHOT ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads what one pair knows, as prompt material and as the observations behind it.
     *
     * <p>
     * The source is the conclusions listing rather than the peer card. The card is the distilled artifact and would
     * be the better text, but it exists only once the dreamer has run — so a card-backed snapshot would be silently
     * empty on a fresh deployment, which is the worst possible shape for a tier that feeds prompt injection. The
     * listing is there from the first derived fact, and it carries real {@link Observation}s, so
     * {@link MemorySnapshot#isObservationsAvailable()} is true rather than a flag apologising for rendered text.
     *
     * <p>
     * {@link MemorySnapshotScope#LOCAL_THEN_GLOBAL} is the one case that can cost two calls: the observer's own view
     * first, and the subject's self-pair only when that view is empty. That is what the scope asks for, and the
     * snapshot reports which one answered.
     */
    private Optional<MemorySnapshot> readSnapshot(MemorySnapshotQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        String workspace = workspaceOf(query.getSubject());
        String observed = peerOf(query.getSubject());
        MemorySnapshotScope scope = query.getScope();

        if (scope != MemorySnapshotScope.GLOBAL) {
            String observer = peerOf(query.getObserver().orElse(query.getSubject()));
            Optional<MemorySnapshot> local = snapshotOf(workspace, observer, observed, query,
                    MemorySnapshotScope.LOCAL);
            if (local.isPresent() || scope == MemorySnapshotScope.LOCAL) {
                return local;
            }
        }
        return snapshotOf(workspace, observed, observed, query, MemorySnapshotScope.GLOBAL);
    }

    private Optional<MemorySnapshot> snapshotOf(String workspace, String observer, String observed,
            MemorySnapshotQuery query, MemorySnapshotScope resolved) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("observer", observer);
        params.put("observed", observed);
        params.put("page", "0");
        params.put("size", String.valueOf(SNAPSHOT_PAGE_SIZE));
        // A 404 is a workspace or peer that does not exist, which for a snapshot is the same answer as a peer with
        // nothing recorded: there is no memory to inject.
        JsonNode body = http.get("/v1/workspaces/" + workspace + "/conclusions", params);
        if (body == null) {
            return Optional.empty();
        }

        Workspace subjectWorkspace = query.getSubject().getWorkspace();
        List<Observation> observations = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();
        boolean truncated = false;
        int budget = query.getMaxTokens();

        for (JsonNode item : body.path("items")) {
            String content = item.path("content").asText("");
            if (content.isEmpty()) {
                continue;
            }
            int wouldBe = estimateTokens(rendered.length() + content.length() + 1);
            if (budget > 0 && wouldBe > budget && rendered.length() > 0) {
                // Stop at the first line that would breach the budget rather than at the last that fits: a
                // half-sentence is worse prompt material than one fact fewer.
                truncated = true;
                break;
            }
            if (rendered.length() > 0) {
                rendered.append('\n');
            }
            rendered.append(content);
            observations.add(toObservation(item, subjectWorkspace));
        }

        if (observations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MemorySnapshot.builder().renderedText(rendered.toString()).resolvedScope(resolved)
                .generatedAt(Instant.now()).tokenCount(estimateTokens(rendered.length())).tokenCountEstimated(true)
                .truncated(truncated || body.path("hasNext").asBoolean(false)).observationsAvailable(true)
                .confidenceAvailable(true).observations(observations).build());
    }

    // ── SEARCH ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Tier 1 recall, with the ranker's own signal breakdown carried through as {@link MemoryHit#getSignals()}. */
    private final class RemoteSearcher implements MemorySearcher {

        @Override
        public List<MemoryHit> search(MemorySearchQuery query) {
            Objects.requireNonNull(query, "query cannot be null");
            if (query.getSessionId().isPresent()) {
                // The recall route has no session parameter — the service ranks a pair's conclusions, and a
                // conclusion is not filed under the session it was derived from. So there is no request this
                // adapter could send that narrows, and the only two things it can do with the id are drop it or
                // refuse. It refuses: sending the query without it returns every session's conclusions, and the
                // caller who named one would read that wider answer as the narrower one they asked for.
                throw new IllegalArgumentException("This backend does not narrow by session (narrowsBySession() =="
                        + " false): AIMON Memory's recall route ranks a pair's conclusions, which outlive the"
                        + " session that produced them, so there is no session parameter to pass. A search cannot"
                        + " be confined to session '" + query.getSessionId().orElseThrow() + "'. Use the CHAT tier,"
                        + " whose dialectic does take a session, or drop the session id and accept an answer across"
                        + " all of them.");
            }
            String workspace = workspaceOf(query.getSubject());
            ObjectNode body = MemoryHttp.object();
            body.put("query", query.getQuery());
            body.put("observer", peerOf(query.getObserver().orElse(query.getSubject())));
            body.put("observed", peerOf(query.getSubject()));
            body.put("limit", query.getTopK());
            body.put("threshold", query.getMinScore());
            // Asked for explicitly: the six-signal breakdown is the thing this backend can say that a hit count
            // cannot, and it is what makes a bad ranking diagnosable from the caller's side.
            body.put("explain", true);

            JsonNode response = http.post("/v1/workspaces/" + workspace + "/recall", body, options.getTimeout());
            if (response == null) {
                return List.of();
            }
            Workspace subjectWorkspace = query.getSubject().getWorkspace();
            List<MemoryHit> hits = new ArrayList<>();
            for (JsonNode hit : response.path("hits")) {
                hits.add(MemoryHit.builder().observation(toObservation(hit.path("conclusion"), subjectWorkspace))
                        .score(clampToUnit(hit.path("score").asDouble(0.0))).confidenceAvailable(true)
                        .signals(signalsOf(hit.path("explain"))).build());
            }
            return List.copyOf(hits);
        }

        @Override
        public boolean ranksByScore() {
            return true;
        }

        /**
         * Always false: recall is scoped to the pair, and a conclusion outlives the session it came from.
         *
         * <p>
         * This flag is a warning, not a licence. It used to be written here as the whole answer — the reasoning was
         * that a caller who reads the flag learns the session was not applied, so answering across every session is
         * disclosed rather than silent. The contract suite rejected that reasoning explicitly: a filter that did not
         * run must not read as one that did, and a flag on the searcher is not read at the call site where the
         * result is consumed. So {@link #search} now throws on a session id rather than widening the answer, and
         * this flag's job is to let a caller find that out before it makes the call.
         */
        @Override
        public boolean narrowsBySession() {
            return false;
        }
    }

    // ── CHAT ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The dialectic: a bounded agentic loop over the pair's memory, on the server.
     *
     * <p>
     * {@code observationsConsidered} comes back empty. The service reports the tool calls the loop made, not the
     * conclusions they returned, and reconstructing observations from tool output would mean parsing rendered text
     * back into rows — a guess presented as provenance. An empty list is the honest answer to "which observations",
     * and the answer itself carries what the loop concluded.
     */
    private DialecticResponse chat(DialecticQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        String workspace = workspaceOf(query.getSubject());
        ObjectNode body = MemoryHttp.object();
        body.put("question", query.getQuestion());
        body.put("observer", peerOf(query.getObserver()));
        body.put("observed", peerOf(query.getSubject()));
        query.getSessionId().filter(session -> !session.isBlank()).ifPresent(session -> body.put("session", session));
        body.put("reasoningLevel", reasoningLevelOf(query.getLevel()));

        JsonNode response = http.post("/v1/workspaces/" + workspace + "/chat", body, options.getChatTimeout());
        String answer = response == null ? "" : response.path("answer").asText("");
        return DialecticResponse.builder().answer(answer).observationsConsidered(List.of())
                // The service does not report usage on this route, and a fabricated zero would be indistinguishable
                // from a call that genuinely cost nothing. `empty()` is the value that means "not measured here".
                .tokenUsage(TokenUsage.empty()).build();
    }

    /**
     * Maps aimon-core's three reasoning levels onto this service's five.
     *
     * <p>
     * The two scales measure the same thing — how many tool-calling iterations the loop may spend — at different
     * granularities. {@code MINIMAL} and {@code MAX} are the ends aimon-core has no name for; they stay reachable
     * through the HTTP API directly and are deliberately not smuggled in behind one of these three.
     */
    private static String reasoningLevelOf(ReasoningLevel level) {
        if (level == null) {
            return "medium";
        }
        switch (level) {
            case FAST :
                return "low";
            case DEEP :
                return "high";
            case BALANCED :
            default :
                return "medium";
        }
    }

    // ── OBSERVE ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Direct injection of a fact the caller already knows, through the same dedup and audit log as derivation. */
    private final class RemoteRecorder implements ObservationRecorder {

        @Override
        public Observation observe(ObservationDraft draft) {
            Objects.requireNonNull(draft, "draft cannot be null");
            String workspace = workspaceOf(draft.getSubject());
            ObjectNode body = MemoryHttp.object();
            body.put("observer", peerOf(draft.getObserver()));
            body.put("observed", peerOf(draft.getSubject()));
            body.put("content", draft.getContent());
            draft.getSessionId().ifPresent(session -> body.put("session", session));

            JsonNode response = http.post("/v1/workspaces/" + workspace + "/conclusions", body, options.getTimeout());
            if (response == null) {
                throw new RemoteMemoryException("workspace " + workspace + " does not exist", 404, "not_found");
            }
            // The draft's own views, not ones rebuilt from the response. The service round-trips peer ids and has
            // no column for a display name, so reconstructing a PeerView from what came back discards a field this
            // method was already holding — and PeerView equality covers the whole Principal, so the caller gets an
            // observation whose subject is unequal to the subject it just passed in.
            return toObservation(response, draft.getSubject().getWorkspace(), draft.getSubject(), draft.getObserver());
        }

        /**
         * Always false: a conclusion's confidence is derived, not supplied.
         *
         * <p>
         * The store computes it from the conclusion's level and from how many times the fact has been independently
         * re-derived, which is a claim about evidence rather than about the caller's certainty. Accepting a number
         * and dropping it would make {@code draft.confidence(0.2)} look stored and read back as something else.
         */
        @Override
        public boolean storesConfidence() {
            return false;
        }
    }

    // ── INGEST ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Hands conversation messages to the service, which queues them for derivation.
     *
     * <p>
     * Each message needs a speaker, because a pair is directed and who said something is what decides whose memory
     * it lands in. aimon-core's request carries one observer and roles, so user-role messages are stored as the
     * observer and assistant-role messages as the configured agent peer — see
     * {@link RemoteMemoryOptions.Builder#agentPeer(String)}. Anything else, a tool result among them, is not
     * conversation and is not sent: it would be recorded as something a participant said.
     */
    private MemoryIngestReceipt ingest(MemoryIngestRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        String workspace = workspaceOf(request.getObserver());
        String observer = peerOf(request.getObserver());

        ObjectNode body = MemoryHttp.object();
        ArrayNode messages = body.putArray("messages");
        for (Message message : request.getMessages()) {
            String speaker = speakerOf(message, observer);
            String content = message.getContent();
            if (speaker == null || content == null || content.isBlank()) {
                continue;
            }
            ObjectNode node = messages.addObject();
            node.put("peer", speaker);
            node.put("content", content);
        }
        if (messages.isEmpty()) {
            return MemoryIngestReceipt.builder().accepted(0).derived(false).build();
        }

        http.post("/v1/workspaces/" + workspace + "/sessions/" + request.getSessionId() + "/messages", body,
                options.getTimeout());
        return MemoryIngestReceipt.builder().accepted(messages.size())
                // Never true. Ingestion returns as soon as the messages are queued; the worker derives afterwards,
                // so there is no request this adapter could make that would wait for it.
                .derived(false).build();
    }

    private String speakerOf(Message message, String observer) {
        if (message.getRole() == null) {
            return null;
        }
        switch (message.getRole()) {
            case USER :
                return observer;
            case ASSISTANT :
                return options.getAgentPeer();
            default :
                return null;
        }
    }

    // ── shared mapping ──────────────────────────────────────────────────────────────────────────────────────────

    private static String workspaceOf(PeerView view) {
        return view.getWorkspace().getId();
    }

    private static String peerOf(PeerView view) {
        return view.getPrincipal().getId();
    }

    private static PeerView viewOf(Workspace workspace, String peer) {
        return PeerView.of(workspace, Principal.user(peer));
    }

    private static PeerView viewOf(Workspace workspace, String peer, PeerView known) {
        if (known != null && known.getPrincipal().getId().equals(peer)) {
            return known;
        }
        return viewOf(workspace, peer);
    }

    /**
     * Turns a {@code ConclusionResponse} into an {@link Observation}.
     *
     * <p>
     * The two vocabularies line up exactly on the one field where they had no reason to: this service's conclusion
     * levels and aimon-core's observation types are the same four names, EXPLICIT through CONTRADICTION. The mapping
     * is by name, and an unknown level becomes {@code INDUCTIVE} rather than throwing — a level added on the server
     * must not take a reader down mid-page.
     */
    private static Observation toObservation(JsonNode conclusion, Workspace workspace) {
        return toObservation(conclusion, workspace, null, null);
    }

    /**
     * Turns a {@code ConclusionResponse} into an {@link Observation}, preferring peers the caller already named.
     *
     * <p>
     * {@code knownSubject} and {@code knownObserver} are the views a write tier was handed and can hand straight
     * back; they are null on the read paths, where the response is the only source there is. Each is used only when
     * its principal id matches the one that came back — a server that answered about a different peer is reporting
     * something, and overwriting it with the caller's view would hide that.
     */
    private static Observation toObservation(JsonNode conclusion, Workspace workspace, PeerView knownSubject,
            PeerView knownObserver) {
        String observed = conclusion.path("observed").asText("");
        String observer = conclusion.path("observer").asText(observed);
        Observation.Builder builder = Observation.builder()
                .id(ObservationId.of(workspace, conclusion.path("id").asText("")))
                .subject(viewOf(workspace, observed, knownSubject)).observer(viewOf(workspace, observer, knownObserver))
                .content(conclusion.path("content").asText(""))
                .type(observationTypeOf(conclusion.path("level").asText("")))
                .sourceMessageIds(textList(conclusion.path("messageIds")))
                .createdAt(instantOf(conclusion.path("createdAt"))).metadata(metadataOf(conclusion));
        if (conclusion.hasNonNull("confidence")) {
            builder.confidence(clampToUnit(conclusion.path("confidence").asDouble()));
        }
        return builder.build();
    }

    private static ObservationType observationTypeOf(String level) {
        for (ObservationType type : ObservationType.values()) {
            if (type.name().equalsIgnoreCase(level)) {
                return type;
            }
        }
        return ObservationType.INDUCTIVE;
    }

    /**
     * The reinforcement counters, kept because they are the part of a conclusion that has no aimon-core field.
     *
     * <p>
     * {@code timesDerived} is how many independent times this fact was concluded and {@code lastReinforcedAt} is
     * when it was last confirmed. Both feed this service's ranking, and both are what a caller looking at two
     * contradictory observations would use to decide which one is current.
     */
    private static Map<String, String> metadataOf(JsonNode conclusion) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (conclusion.hasNonNull("timesDerived")) {
            metadata.put("timesDerived", conclusion.path("timesDerived").asText());
        }
        if (conclusion.hasNonNull("lastReinforcedAt")) {
            metadata.put("lastReinforcedAt", conclusion.path("lastReinforcedAt").asText());
        }
        if (conclusion.hasNonNull("session")) {
            metadata.put("session", conclusion.path("session").asText());
        }
        return metadata;
    }

    /** The ranker's six signals, under the names the service's own explain block uses. */
    private static Map<String, Double> signalsOf(JsonNode explain) {
        if (explain == null || explain.isMissingNode() || explain.isNull()) {
            return Map.of();
        }
        Map<String, Double> signals = new LinkedHashMap<>();
        for (String signal : List.of("sem", "kw", "ent", "reinf", "rec", "lvl")) {
            if (explain.hasNonNull(signal)) {
                signals.put(signal, explain.path(signal).asDouble());
            }
        }
        return Map.copyOf(signals);
    }

    private static List<String> textList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    private static Instant instantOf(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return Instant.now();
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    /**
     * Keeps a score inside the range {@link MemoryHit} accepts.
     *
     * <p>
     * The fused score is a weighted sum of six signals whose weights sum to 1.00, so it is already in range by
     * construction. The clamp is here because {@code MemoryHit}'s builder throws on anything outside it, and a
     * server-side weight change that pushed one hit to 1.0000001 would otherwise take down the whole search rather
     * than return a slightly odd number.
     */
    private static double clampToUnit(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int estimateTokens(int characters) {
        return (characters + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
