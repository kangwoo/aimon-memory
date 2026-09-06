package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.store.Drafts;
import at.aimon.memory.store.StoreTestBase;
import at.aimon.memory.store.Vectors;
import at.aimon.memory.testkit.db.Explain;

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

    private static final String ALIVE = "c.deleted_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now())";
    private static final String PAIR = "c.workspace_name = ? AND c.observer = ? AND c.observed = ?";

    private PairKey pair;
    private String vector;
    private final java.util.List<String> conclusionIds = new java.util.ArrayList<>();

    @BeforeEach
    void seedAndAnalyse() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        conclusionIds.clear();
        // Each row carries a term unique to it. A text index earns its place on selective queries;
        // asking for a word every row contains would make the pair scan genuinely cheaper, and the
        // test would then be measuring the planner's arithmetic rather than the index's reachability.
        for (int i = 0; i < 200; i++) {
            conclusionIds.add(conclusions
                    .upsert(Drafts.explicit(pair, "s1", "alice fact number " + i + " about marker" + i + " topic"))
                    .conclusionId());
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
        String plan = Explain.plan("SELECT c.id FROM conclusions c WHERE c.embedding IS NOT NULL"
                + " ORDER BY c.embedding <=> ?::vector LIMIT 10", vector);
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
        String plan = Explain.plan("SELECT c.id FROM conclusions c WHERE " + PAIR + " AND " + ALIVE
                + " AND to_tsvector('simple', c.content_analyzed) @@ to_tsquery('simple', ?)" + " ORDER BY ts_rank_cd("
                + "   to_tsvector('simple', c.content_analyzed), to_tsquery('simple', ?)) DESC," + " c.id LIMIT 10",
                WORKSPACE, "alice", "alice", "marker7", "marker7");
        assertThat(plan).contains("ix_concl_fts");
    }

    /**
     * The document-frequency statement reaches the text index, EXPLAINed through the statement
     * {@code corpusStats} actually runs.
     *
     * <p>It is on the recall path twice — once from {@code keyword} and once from
     * {@code fillMissingKeyword} — and it was not covered here at all. Covering it is what showed
     * that the {@code LEFT JOIN … GROUP BY} shape it used to have never reached this index: with the
     * tsquery in a join condition the planner discards the parameterised index path whenever a plain
     * scan of the pair is cheaper, and applies {@code to_tsvector(…) @@ to_tsquery(…)} as a join
     * filter over every live row in the pair instead. That was true of the {@code plainto_tsquery}
     * form too, so it is a defect this change inherited rather than one it introduced.
     * {@link ConclusionRepository#DOCUMENT_FREQUENCY_SQL} says what the {@code LATERAL} buys and what
     * it was measured at.
     *
     * <p>The two parallel arrays are bound with {@code setObject}, which the driver maps to an array
     * parameter — verified against postgresql-42.7.13 for exactly this {@code unnest(?, ?)} shape, so
     * neither {@code createArrayOf} nor a literal array in the SQL text is needed.
     */
    @Test
    void documentFrequencyCountingReachesTheFullTextIndex() {
        assertThat(Explain.plan(ConclusionRepository.DOCUMENT_FREQUENCY_SQL, new String[]{"marker7"},
                new String[]{"'marker7'"}, WORKSPACE, "alice", "alice")).contains("ix_concl_fts");
    }

    /**
     * The partial indexes are the ones most easily lost. Each is declared {@code WHERE deleted_at IS
     * NULL}, and Postgres will only use it if the query's own predicate implies that — which it does
     * today because the live-row filter names the column outright. Rewriting that filter as anything
     * cleverer would silently drop these.
     *
     * <p>The {@code content_norm} half is the case this class's header calls out by name: since V13
     * {@code ix_concl_norm} is an <em>expression</em> index, on {@code md5(content_norm)}, so the
     * predicate has to be written the way the index is or it is not an option at all. Measured with the
     * {@code md5} dropped, the plan falls back to a bitmap scan of {@code ix_concl_pair} — a stage that
     * silently became a pair scan, with no error anywhere to say so.
     *
     * <p>EXPLAINed from {@link ConclusionRepository#NORM_LOOKUP} rather than from a copy of it, for the
     * reason {@code entityBoostReachesThePairScopedEdgeIndex} was rewritten: an assertion that passes
     * for a query no caller issues is the one thing an index-usage gate must not do. The second half of
     * that constant is asserted too — the exact equality is what makes an md5 collision cost a heap
     * tuple instead of a false REINFORCE, and deleting it would leave this plan otherwise unchanged.
     */
    @Test
    void dedupLookupsReachTheirPartialIndexes() {
        assertThat(Explain.plan(
                "SELECT c.id FROM conclusions c WHERE " + PAIR + " AND c.deleted_at IS NULL AND c.content_hash = ?",
                WORKSPACE, "alice", "alice", "0".repeat(64))).contains("ix_concl_hash");

        String plan = Explain.plan("SELECT c.id FROM conclusions c WHERE " + PAIR + " AND c.deleted_at IS NULL AND "
                + ConclusionRepository.NORM_LOOKUP, WORKSPACE, "alice", "alice", "anything", "anything");
        assertThat(plan).contains("ix_concl_norm");
        // The index condition renders `md5(content_norm) = md5(...)`, which does not contain this;
        // only the recheck's own Filter line does.
        assertThat(plan).contains("content_norm =");
    }

    @Test
    void pairListingReachesThePairIndex() {
        assertThat(Explain.plan("SELECT c.id FROM conclusions c WHERE " + PAIR + " AND " + ALIVE
                + " ORDER BY c.created_at DESC LIMIT 50", WORKSPACE, "alice", "alice")).contains("ix_concl_pair");
    }

    @Test
    void expirySweepReachesItsPartialIndex() {
        assertThat(Explain.plan("SELECT c.id FROM conclusions c WHERE c.deleted_at IS NULL"
                + " AND c.expires_at IS NOT NULL AND c.expires_at <= now()" + " ORDER BY c.expires_at LIMIT 100"))
                .contains("ix_concl_expiry");
    }

    @Test
    void reconcilerSyncSweepReachesItsPartialIndex() {
        assertThat(Explain.plan("SELECT c.id FROM conclusions c WHERE c.deleted_at IS NULL"
                + " AND c.sync_state = 'pending' ORDER BY c.created_at LIMIT 100")).contains("ix_concl_sync");
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

    /**
     * The HNSW index on entities serves node resolution, not the boost.
     *
     * <p>This is the query {@code upsert} runs to decide whether a name is a node the workspace
     * already has: a workspace-wide nearest neighbour, which is exactly what the index is shaped for.
     * The boost path deliberately does not use it — it joins to {@code entity_links} first so that a
     * pair's top-k is drawn from the pair's own entities, and that ordering cannot come from an index
     * over the whole workspace.
     */
    @Test
    void entityResolutionReachesTheEntityVectorIndex() {
        entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        Explain.analyse("entities");
        assertThat(
                Explain.plan(
                        "SELECT e.id FROM entities e WHERE e.workspace_name = ?"
                                + " AND e.embedding IS NOT NULL ORDER BY e.embedding <=> ?::vector LIMIT 1",
                        WORKSPACE, vector))
                .contains("ix_entity_hnsw");
    }

    /**
     * The boost path's own index, EXPLAINed through the statement {@code match} actually runs.
     *
     * <p>This used to assert against a hand-written single-table lookup on {@code entity_links}. That
     * stopped describing the boost the moment the candidate set became a join to the pair's own
     * entities: the assertion went on passing for a query no caller issues, which is the one thing an
     * index-usage gate must not do.
     */
    @Test
    void entityBoostReachesThePairScopedEdgeIndex() {
        seedEntityGraph();
        assertThat(Explain.plan(EntityRepository.MATCH_SQL, vector, WORKSPACE, "alice", "alice", WORKSPACE, vector, 10))
                .contains("ix_elink_entity");
    }

    /**
     * Nodes shared across the workspace, edges split between two pairs.
     *
     * <p>Both halves matter. Without edges the planner has nothing to cost and picks whichever index
     * it reaches first, which is how the previous version of this assertion passed against a query
     * shape that no longer existed. Without a second pair's edges on the same nodes the pair filter is
     * not selective, and the plan says nothing about whether it can be answered from the pair-scoped
     * index.
     */
    private void seedEntityGraph() {
        PairKey other = seedPair("bob", "carol");
        seedSession("s2");
        String theirs = conclusions.upsert(Drafts.explicit(other, "s2", "bob fact about seoul")).conclusionId();
        for (int i = 0; i < 200; i++) {
            var node = entities.upsert(WORKSPACE, "entity" + i, "THING", Drafts.embed("entity" + i));
            entities.link(WORKSPACE, node.id(), conclusionIds.get(i), pair);
            entities.link(WORKSPACE, node.id(), theirs, other);
        }
        Explain.analyse("entities", "entity_links", "conclusions");
    }

    /**
     * The dialectic's message tools, EXPLAINed with their membership predicate attached.
     *
     * <p>The scope comes from {@link MessageRepository#audibleTo} rather than being written out here,
     * because a copy is what let this assertion drift: the real queries grew a correlated {@code
     * EXISTS} over the membership windows, which changes the join order the planner considers, and
     * the gate went on proving reachability for the predicate they used to have.
     */
    @Test
    void dialecticMessageToolsReachTheirIndexes() {
        seedMessagesAndMembership();

        MessageRepository.Scope scope = MessageRepository.audibleTo(WORKSPACE, "alice", "s1");
        java.util.List<Object> params = new java.util.ArrayList<>(scope.params());
        params.add("marker7");
        assertThat(Explain.plan("SELECT m.id FROM messages m WHERE " + scope.sql()
                + " AND to_tsvector('simple', m.content) @@ websearch_to_tsquery('simple', ?)"
                + " ORDER BY m.created_at DESC LIMIT 10", params.toArray())).contains("ix_message_fts");
    }

    /** The membership predicate has an index of its own, or it is a scan per message row. */
    @Test
    void theMembershipPredicateReachesTheWindowIndex() {
        seedMessagesAndMembership();

        MessageRepository.Scope scope = MessageRepository.audibleTo(WORKSPACE, "alice", "s1");
        assertThat(Explain.plan("SELECT m.id FROM messages m WHERE " + scope.sql() + " LIMIT 10",
                scope.params().toArray())).contains("ix_speer_window_lookup");
    }

    private void seedMessagesAndMembership() {
        peers.getOrCreate(WORKSPACE, "alice", java.util.Map.of(), java.util.Map.of());
        sessionPeers.join(WORKSPACE, "s1", "alice");
        java.util.List<MessageRepository.NewMessage> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batch.add(new MessageRepository.NewMessage("alice", "message about marker" + i, 4, java.util.Map.of()));
        }
        long start = sessions.nextSequence(WORKSPACE, "s1", batch.size());
        messages.insertBatch(WORKSPACE, "s1", start, batch);
        Explain.analyse("messages", "session_peer_windows");
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
        seedMessagesAndMembership();

        assertThat(Explain.plan(
                "SELECT m.id FROM messages m WHERE m.content ILIKE '%' || ? || '%' ESCAPE '\\'" + " LIMIT 10",
                "marker7")).contains("ix_message_trgm");
    }

    @Test
    void queuePollingReachesItsPartialIndex() {
        queue.enqueue(at.aimon.memory.core.key.WorkUnitKey.representation(WORKSPACE, "s1", pair),
                java.util.Map.of("message_id", 1), 10);
        Explain.analyse("queue");
        assertThat(Explain
                .plan("SELECT q.work_unit_key FROM queue q WHERE q.processed = FALSE" + " GROUP BY q.work_unit_key"))
                .contains("ix_queue_pending");
    }
}
