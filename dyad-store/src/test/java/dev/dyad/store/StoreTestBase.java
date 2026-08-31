package dev.dyad.store;

import dev.dyad.core.key.PairKey;
import dev.dyad.text.AnalyzerRegistry;
import dev.dyad.store.repo.CollectionRepository;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.EntityRepository;
import dev.dyad.store.repo.EventLogRepository;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.SessionPeerRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared wiring for the persistence tests.
 *
 * <p>Repositories are constructed by hand rather than through a Spring context. These tests are
 * about SQL, and a container start per class would add seconds to every one of them to prove
 * something the application tests already prove.
 */
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
