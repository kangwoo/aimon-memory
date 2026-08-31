package dev.dyad.memory;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.memory.derive.ConclusionWriter;
import dev.dyad.memory.derive.DeriverService;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.memory.dialectic.ToolRegistry;
import dev.dyad.memory.ingest.MessageIngestionService;
import dev.dyad.recall.ProvenanceService;
import dev.dyad.recall.RecallService;
import dev.dyad.store.WorkspaceSettingsService;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.DreamRepository;
import dev.dyad.store.repo.EntityRepository;
import dev.dyad.store.repo.EventLogRepository;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.PeerCardRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.SessionPeerRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import dev.dyad.testkit.stub.StubEmbedder;
import dev.dyad.testkit.stub.StubLlmClient;
import dev.dyad.text.AnalyzerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the memory pipeline against a real database, a stub model and a fixed clock. */
public abstract class MemoryTestBase {

    protected static final String WORKSPACE = "ws";
    protected static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    protected static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    protected JdbcClient jdbc;
    protected WorkspaceRepository workspaces;
    protected PeerRepository peers;
    protected SessionRepository sessions;
    protected SessionPeerRepository sessionPeers;
    protected MessageRepository messages;
    protected CollectionRepository collections;
    protected EventLogRepository events;
    protected ConclusionRepository conclusions;
    protected EntityRepository entities;
    protected QueueRepository queue;
    protected DreamRepository dreams;
    protected PeerCardRepository cards;

    protected WorkspaceSettingsService settings;
    protected EntityPipeline entityPipeline;
    protected ConclusionWriter writer;
    protected MessageIngestionService ingestion;
    protected RecallService recall;
    protected ProvenanceService provenance;
    protected ToolRegistry tools;
    protected final StubEmbedder embedder = new StubEmbedder();

    @BeforeEach
    void setUpMemory() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        workspaces = new WorkspaceRepository(jdbc);
        peers = new PeerRepository(jdbc);
        sessions = new SessionRepository(jdbc);
        sessionPeers = new SessionPeerRepository(jdbc);
        messages = new MessageRepository(jdbc);
        collections = new CollectionRepository(jdbc);
        events = new EventLogRepository(jdbc);
        settings = new WorkspaceSettingsService(workspaces, new AnalyzerRegistry());
        conclusions = new ConclusionRepository(jdbc, events, collections, settings);
        entities = new EntityRepository(jdbc);
        queue = new QueueRepository(jdbc);
        dreams = new DreamRepository(jdbc);
        cards = new PeerCardRepository(jdbc);

        entityPipeline = new EntityPipeline(entities, embedder);
        writer = new ConclusionWriter(conclusions, entityPipeline, embedder, settings);
        ingestion =
                new MessageIngestionService(
                        workspaces, peers, sessions, sessionPeers, messages, collections, queue, settings);
        recall = new RecallService(conclusions, entities, settings, embedder, CLOCK);
        provenance = new ProvenanceService(entities, conclusions, messages);
        tools = new ToolRegistry(recall, provenance, messages, conclusions);
    }

    protected DeriverService deriverReturning(String json) {
        return new DeriverService(StubLlmClient.returning(json), writer);
    }

    protected DeriverService deriverAnswering(Function<LlmRequest, String> responder) {
        return new DeriverService(new StubLlmClient(responder), writer);
    }

    protected LlmClient stub(String json) {
        return StubLlmClient.returning(json);
    }

    protected PairKey seedPair(String observer, String observed) {
        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, observer, Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, observed, Map.of(), Map.of());
        PairKey pair = new PairKey(WORKSPACE, observer, observed);
        collections.getOrCreate(pair);
        return pair;
    }

    protected void seedSession(String name) {
        workspaces.getOrCreate(WORKSPACE, Map.of(), Map.of());
        sessions.getOrCreate(WORKSPACE, name, Map.of(), Map.of());
    }
}
