package at.aimon.memory.recall;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.EventLogRepository;
import at.aimon.memory.store.repo.MessageRepository;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.testkit.stub.StubEmbedder;
import at.aimon.memory.text.AnalyzerRegistry;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.Normalizer;

/**
 * Ranking tests run against a fixed clock and a deterministic embedder.
 *
 * <p>Both are required for the fixtures to mean anything: the recency signal reads the clock, and a
 * real embedding provider would return slightly different vectors between runs. With these pinned,
 * the six-decimal comparison tests the implementation rather than the weather.
 */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
public abstract class RecallTestBase {

    protected static final String WORKSPACE = "ws";
    protected static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    protected JdbcClient jdbc;
    protected WorkspaceRepository workspaces;
    protected PeerRepository peers;
    protected SessionRepository sessions;
    protected MessageRepository messages;
    protected CollectionRepository collections;
    protected ConclusionRepository conclusions;
    protected EntityRepository entities;
    protected WorkspaceSettingsService settings;
    protected RecallService recall;
    protected ProvenanceService provenance;

    protected final StubEmbedder embedder = new StubEmbedder();
    protected final StubAnalyzer analyzer = new StubAnalyzer();

    @BeforeEach
    void setUpRecall() {
        jdbc = JdbcClient.create(PostgresSupport.dataSource());
        PostgresSupport.truncateAll();

        workspaces = new WorkspaceRepository(jdbc);
        peers = new PeerRepository(jdbc);
        sessions = new SessionRepository(jdbc);
        messages = new MessageRepository(jdbc);
        collections = new CollectionRepository(jdbc);
        entities = new EntityRepository(jdbc);
        settings = new WorkspaceSettingsService(workspaces, new AnalyzerRegistry());
        conclusions = new ConclusionRepository(jdbc, new EventLogRepository(jdbc), collections, settings);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        recall = new RecallService(conclusions, entities, settings, embedder, clock);
        provenance = new ProvenanceService(entities, conclusions, messages);
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

    protected String store(PairKey pair, String session, String content) {
        return store(pair, session, content, ConclusionLevel.EXPLICIT, List.of());
    }

    protected String store(PairKey pair, String session, String content, ConclusionLevel level,
            List<String> entityNames) {
        String norm = Normalizer.normalize(content);
        ConclusionDraft draft = ConclusionDraft.builder().pair(pair)
                .sessionName(level == ConclusionLevel.EXPLICIT ? session : null).content(content).contentNorm(norm)
                .contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm)).level(level)
                .entityNames(entityNames).embedding(embedder.embed(content, EmbedPurpose.DOCUMENT)).actor(Actor.DERIVER)
                .build();
        return conclusions.upsert(draft).conclusionId();
    }

    /** Pin a row's ranking inputs directly, so a fixture can exercise a signal without waiting. */
    protected void setRankingState(String conclusionId, int timesDerived, Instant lastReinforcedAt) {
        jdbc.sql("UPDATE conclusions SET times_derived = ?, last_reinforced_at = ? WHERE id = ?")
                .params(timesDerived, java.sql.Timestamp.from(lastReinforcedAt), conclusionId).update();
    }

    protected void linkEntity(PairKey pair, String conclusionId, String entityName) {
        var entity = entities.upsert(WORKSPACE, entityName, null, embedder.embed(entityName, EmbedPurpose.ENTITY));
        entities.link(WORKSPACE, entity.id(), conclusionId, pair);
    }
}
