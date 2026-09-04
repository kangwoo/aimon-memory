package at.aimon.memory.store;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.EventLogRepository;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.SessionPeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.text.AnalyzerRegistry;

/**
 * Shared wiring for the persistence tests.
 *
 * <p>Repositories are constructed by hand rather than through a Spring context. These tests are
 * about SQL, and a container start per class would add seconds to every one of them to prove
 * something the application tests already prove.
 */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
public abstract class StoreTestBase {

    protected static final String WORKSPACE = "ws";

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
    protected WorkspaceSettingsService settings;

    @BeforeEach
    void setUpStore() {
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
