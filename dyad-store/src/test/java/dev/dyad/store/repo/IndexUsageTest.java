package dev.dyad.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.key.PairKey;
import dev.dyad.store.Drafts;
import dev.dyad.store.StoreTestBase;
import dev.dyad.store.Vectors;
import dev.dyad.testkit.db.Explain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every index in the schema has to be reachable by the query it was created for.
 *
 * <p>Indexes arrive per phase precisely because an unused one is not free — it slows every insert to
 * serve a query nobody makes. The corollary is that an index which exists but which the real query
 * shape cannot use is the worst of both: full write cost, no read benefit, and nothing anywhere says
 * so. This catches the usual causes — a predicate that no longer implies a partial index, an ORDER BY
 * that stops matching the operator class, a cast that defeats an expression index.
 *
 * <p>These assert reachability, not plan choice. On a small table Postgres will rightly prefer a
 * sequential scan; the question being asked here is whether the index is usable at all.
 */
class IndexUsageTest extends StoreTestBase {

    private static final String ALIVE =
            "c.deleted_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now())";
    private static final String PAIR = "c.workspace_name = ? AND c.observer = ? AND c.observed = ?";

    private PairKey pair;
    private String vector;

    @BeforeEach
    void seedAndAnalyse() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        // Each row carries a term unique to it. A text index earns its place on selective queries;
        // asking for a word every row contains would make the pair scan genuinely cheaper, and the
        // test would then be measuring the planner's arithmetic rather than the index's reachability.
        for (int i = 0; i < 200; i++) {
            conclusions.upsert(
                    Drafts.explicit(pair, "s1", "alice fact number " + i + " about marker" + i + " topic"));
        }
        vector = Vectors.toLiteral(Drafts.embed("alice fact"));
        Explain.analyse("conclusions", "entities", "entity_links", "messages", "queue");
    }

    /**
     * The vector index is only usable when the ORDER BY operator matches its operator class — swap
     * {@code <=>} for {@code <->} and the index silently stops being an option.
     *
     * <p>Asserted without the pair filter on purpose. With one, the planner chooses between walking
     * the graph and scanning the pair's own rows, and at test scale scanning a few hundred rows
     * genuinely wins. Measured on 60k conclusions: with 300 rows per pair it scans the pair, and with
     * 50k rows in one pair it switches to the HNSW index on its own. Both are the right call, so
     * pinning either would be pinning the planner's arithmetic rather than this schema.
     */
    @Test
    void theVectorIndexMatchesTheDistanceOperatorRecallUses() {
        String plan =
                Explain.plan(
                        "SELECT c.id FROM conclusions c WHERE c.embedding IS NOT NULL"
                                + " ORDER BY c.embedding <=> ?::vector LIMIT 10",
                        vector);
        assertThat(plan).contains("ix_concl_hnsw");
    }

    /**
     * The text index has to carry the pair columns, or it is unreachable.
     *
     * <p>A GIN index over the tsvector alone was never chosen: reaching one pair through it means
     * scanning every workspace's terms, which costs more than reading the pair's own rows. Folding
     * the scope columns in with btree_gin lets one index answer both halves of the predicate.
     */
    @Test
    void keywordRecallReachesTheFullTextIndex() {
        String plan =
                Explain.plan(
                        "SELECT c.id FROM conclusions c WHERE " + PAIR + " AND " + ALIVE
                                + " AND to_tsvector('simple', c.content_analyzed) @@ to_tsquery('simple', ?)"
                                + " ORDER BY ts_rank_cd("
                                + "   to_tsvector('simple', c.content_analyzed), to_tsquery('simple', ?)) DESC,"
                                + " c.id LIMIT 10",
                        WORKSPACE, "alice", "alice", "marker7", "marker7");
        assertThat(plan).contains("ix_concl_fts");
    }

    /**
     * The partial indexes are the ones most easily lost. Each is declared {@code WHERE deleted_at IS
     * NULL}, and Postgres will only use it if the query's own predicate implies that — which it does
     * today because the live-row filter names the column outright. Rewriting that filter as anything
     * cleverer would silently drop these.
     */
    @Test
    void dedupLookupsReachTheirPartialIndexes() {
        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE " + PAIR
                                        + " AND c.deleted_at IS NULL AND c.content_hash = ?",
                                WORKSPACE, "alice", "alice", "0".repeat(64)))
                .contains("ix_concl_hash");

        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE " + PAIR
                                        + " AND c.deleted_at IS NULL AND c.content_norm = ?",
                                WORKSPACE, "alice", "alice", "anything"))
                .contains("ix_concl_norm");
    }

    @Test
    void pairListingReachesThePairIndex() {
        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE " + PAIR + " AND " + ALIVE
                                        + " ORDER BY c.created_at DESC LIMIT 50",
                                WORKSPACE, "alice", "alice"))
                .contains("ix_concl_pair");
    }

    @Test
    void expirySweepReachesItsPartialIndex() {
        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE c.deleted_at IS NULL"
                                        + " AND c.expires_at IS NOT NULL AND c.expires_at <= now()"
                                        + " ORDER BY c.expires_at LIMIT 100"))
                .contains("ix_concl_expiry");
    }

    @Test
    void reconcilerSyncSweepReachesItsPartialIndex() {
        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE c.deleted_at IS NULL"
                                        + " AND c.sync_state = 'pending' ORDER BY c.created_at LIMIT 100"))
                .contains("ix_concl_sync");
    }

    /** Only the dreamer writes source_ids and only the chain walk reads them; jsonb_path_ops is why. */
    @Test
    void reasoningChainTraversalReachesTheJsonbIndex() {
        assertThat(
                        Explain.plan(
                                "SELECT c.id FROM conclusions c WHERE c.workspace_name = ?"
                                        + " AND c.deleted_at IS NULL AND c.source_ids @> to_jsonb(?::text)",
                                WORKSPACE, "some-id"))
                .contains("ix_concl_tree");
    }

    @Test
    void entityMatchingReachesTheEntityVectorIndex() {
        entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        Explain.analyse("entities");
        assertThat(
                        Explain.plan(
                                "SELECT e.id FROM entities e WHERE e.workspace_name = ?"
                                        + " AND e.embedding IS NOT NULL ORDER BY e.embedding <=> ?::vector LIMIT 10",
                                WORKSPACE, vector))
                .contains("ix_entity_hnsw");
    }

    @Test
    void dialecticMessageToolsReachTheirIndexes() {
        peers.getOrCreate(WORKSPACE, "alice", java.util.Map.of(), java.util.Map.of());
        java.util.List<MessageRepository.NewMessage> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batch.add(new MessageRepository.NewMessage("alice", "message about marker" + i, 4, java.util.Map.of()));
        }
        long start = sessions.nextSequence(WORKSPACE, "s1", batch.size());
        messages.insertBatch(WORKSPACE, "s1", start, batch);
        Explain.analyse("messages");

        assertThat(
                        Explain.plan(
                                "SELECT m.id FROM messages m WHERE m.workspace_name = ? AND m.session_name = ?"
                                        + " AND to_tsvector('simple', m.content) @@ websearch_to_tsquery('simple', ?)"
                                        + " ORDER BY m.created_at DESC LIMIT 10",
                                WORKSPACE, "s1", "marker7"))
                .contains("ix_message_fts");

    }

    /**
     * The substring tool has to use an operator the trigram index understands.
     *
     * <p>It used {@code position()}, which pg_trgm offers no index support for at all: measured on
     * 50k messages that was a sequential scan on every call, and the dialectic makes several per
     * question. {@code ILIKE} is index-backed, which is why the query is written that way and why the
     * caller's wildcards have to be escaped before they reach it.
     *
     * <p>Asserted without the workspace filter, for the same reason as the vector index: at test
     * scale the planner rightly prefers a small btree scan, and pinning that choice would test the
     * planner rather than the schema. What matters here is that the operator and the opclass match —
     * swapping back to {@code position()} or {@code =} makes this index unreachable at any scale.
     */
    @Test
    void substringSearchUsesAnOperatorTheTrigramIndexSupports() {
        java.util.List<MessageRepository.NewMessage> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batch.add(new MessageRepository.NewMessage("alice", "message about marker" + i, 4, java.util.Map.of()));
        }
        peers.getOrCreate(WORKSPACE, "alice", java.util.Map.of(), java.util.Map.of());
        messages.insertBatch(
                WORKSPACE, "s1", sessions.nextSequence(WORKSPACE, "s1", batch.size()), batch);
        Explain.analyse("messages");

        assertThat(
                        Explain.plan(
                                "SELECT m.id FROM messages m WHERE m.content ILIKE '%' || ? || '%' ESCAPE '\\'"
                                        + " LIMIT 10",
                                "marker7"))
                .contains("ix_message_trgm");
    }

    @Test
    void queuePollingReachesItsPartialIndex() {
        queue.enqueue(
                dev.dyad.core.key.WorkUnitKey.representation(WORKSPACE, "s1", pair),
                java.util.Map.of("message_id", 1), 10);
        Explain.analyse("queue");
        assertThat(
                        Explain.plan(
                                "SELECT q.work_unit_key FROM queue q WHERE q.processed = FALSE"
                                        + " GROUP BY q.work_unit_key"))
                .contains("ix_queue_pending");
    }
}
