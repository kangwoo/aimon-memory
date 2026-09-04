package at.aimon.memory.engine;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.engine.derive.ConclusionWriter;
import at.aimon.memory.engine.derive.DeriverService;
import at.aimon.memory.engine.dialectic.ToolRegistry;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.engine.ingest.MessageIngestionService;
import at.aimon.memory.recall.ProvenanceService;
import at.aimon.memory.recall.RecallService;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.EventLogRepository;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.PeerCardRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.SessionPeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.testkit.stub.StubEmbedder;
import at.aimon.memory.testkit.stub.StubLlmClient;
import at.aimon.memory.text.AnalyzerRegistry;

/** Wires the memory pipeline against a real database, a stub model and a fixed clock. */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
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
        ingestion = new MessageIngestionService(workspaces, peers, sessions, sessionPeers, messages, collections, queue,
                settings);
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
