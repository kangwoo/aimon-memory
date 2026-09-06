package at.aimon.memory.store.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.id.NanoId;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.CorpusStats;
import at.aimon.memory.core.model.DedupOutcome;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.model.Page;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.core.model.SyncState;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.EventLog;
import at.aimon.memory.store.Jsonb;
import at.aimon.memory.store.RowMappers;
import at.aimon.memory.store.Sql;
import at.aimon.memory.store.TsQuery;
import at.aimon.memory.store.Vectors;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.dedup.DedupPolicy;
import at.aimon.memory.store.filter.CompiledFilter;
import at.aimon.memory.store.filter.FilterCompiler;
import at.aimon.memory.store.filter.FilterSchema;
import at.aimon.memory.text.Bm25;

/**
 * Conclusion persistence: the two retrieval paths, and the three-stage write.
 *
 * <p>Rows are never scoped by anything looser than the pair. Every query in this class starts from
 * {@code workspace_name / observer / observed}, and there is no method that omits them.
 */
@Repository
public class ConclusionRepository implements ConclusionStore {

    private static final String ALIVE = "c.deleted_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now())";
    private static final String PAIR_SCOPE = "c.workspace_name = ? AND c.observer = ? AND c.observed = ?";

    /**
     * Dedup stage 2's predicate, package-private so the index gate can EXPLAIN it rather than a copy.
     *
     * <p>Two halves that have to stay together. {@code ix_concl_norm} is an index on
     * {@code md5(content_norm)} — V13 made it one, because a btree over the content itself refused any
     * entry above 2704 bytes and so let the length of a conclusion decide whether it could be stored.
     * The first half is what reaches that index; the second is what makes the stage exact, so an md5
     * collision costs one heap tuple fetched and discarded instead of a false REINFORCE.
     *
     * <p>Dropping either half is silent. Without {@code md5(...)} the plan falls back to a scan of
     * {@code ix_concl_pair} and nothing errors; without the equality the wrong row can be returned and
     * nothing errors either. {@code IndexUsageTest} asserts on both.
     */
    static final String NORM_LOOKUP = "md5(c.content_norm) = md5(?) AND c.content_norm = ?";

    private final JdbcClient jdbc;
    private final EventLog events;
    private final CollectionRepository collections;
    private final WorkspaceSettingsService settings;
    private final FilterCompiler filters = new FilterCompiler(FilterSchema.CONCLUSIONS);

    public ConclusionRepository(JdbcClient jdbc, EventLog events, CollectionRepository collections,
            WorkspaceSettingsService settings) {
        this.jdbc = jdbc;
        this.events = events;
        this.collections = collections;
        this.settings = settings;
    }

    /**
     * Dedup thresholds are resolved per workspace rather than fixed at construction.
     *
     * <p>The right cosine threshold depends on the corpus — technical text clusters far more tightly
     * than conversational text — so it is exposed as configuration, and configuration that the code
     * does not actually read is worse than no configuration at all.
     */
    private DedupPolicy dedupFor(String workspaceName) {
        return new DedupPolicy(settings.forWorkspace(workspaceName).dedup());
    }

    // ── Retrieval ───────────────────────────────────────────────────────────

    @Override
    public List<ScoredConclusion> semantic(PairKey pair, float[] q, int limit, Filter f) {
        CompiledFilter compiled = filters.compile(f);
        String vector = Vectors.toLiteral(q);
        List<Object> params = new ArrayList<>(pairParams(pair));
        params.addAll(compiled.params());
        params.add(vector);
        params.add(limit);
        return jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS + ", (c.embedding <=> ?::vector) AS distance"
                + " FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE + " AND c.embedding IS NOT NULL AND "
                + compiled.sql() + " ORDER BY c.embedding <=> ?::vector LIMIT ?").params(prepend(vector, params))
                .query((rs, i) -> ScoredConclusion.raw(RowMappers.CONCLUSION.mapRow(rs, i),
                        Vectors.similarityFromDistance(rs.getDouble("distance"))))
                .list();
    }

    /**
     * Keyword retrieval.
     *
     * <p>Postgres finds the candidates through the GIN index and counts document frequencies; the
     * BM25 arithmetic happens in Java. Splitting it that way is what makes this signal assertable to
     * six decimal places — {@code ts_rank_cd} has no fixed definition across server versions, and a
     * ranking test that a minor upgrade can break is not a ranking test.
     */
    @Override
    public List<ScoredConclusion> keyword(PairKey pair, String analyzed, int limit, Filter f) {
        List<String> terms = tokenize(analyzed);
        String tsquery = TsQuery.orOf(terms);
        if (tsquery.isEmpty()) {
            return List.of();
        }
        CompiledFilter compiled = filters.compile(f);

        List<Object> params = new ArrayList<>(pairParams(pair));
        params.addAll(compiled.params());
        params.add(tsquery);
        params.add(tsquery);
        params.add(limit);
        // ts_rank_cd orders the candidates; it does not score them. A bare LIMIT here would hand back
        // an arbitrary subset of the matches — Postgres is free to return any rows when nothing is
        // ordered — so on a pair with more matches than the limit the best BM25 hit could be missed,
        // and which rows came back could change between runs. Ranking stays in Java, where it is
        // assertable; the database only has to pick a sensible candidate set. The id tie-break makes
        // that pick reproducible.
        List<Conclusion> candidates = jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE "
                + PAIR_SCOPE + " AND " + ALIVE + " AND " + compiled.sql()
                + " AND to_tsvector('simple', c.content_analyzed) @@ to_tsquery('simple', ?)" + " ORDER BY ts_rank_cd("
                + "   to_tsvector('simple', c.content_analyzed), to_tsquery('simple', ?)) DESC," + " c.id" + " LIMIT ?")
                .params(params).query(RowMappers.CONCLUSION).list();
        if (candidates.isEmpty()) {
            return List.of();
        }

        CorpusStats stats = corpusStats(pair, terms);
        List<ScoredConclusion> scored = new ArrayList<>(candidates.size());
        for (Conclusion candidate : candidates) {
            scored.add(
                    ScoredConclusion.raw(candidate, Bm25.score(terms, tokenize(candidate.contentAnalyzed()), stats)));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }

    /**
     * Per-pair BM25 statistics.
     *
     * <p>Public because fusion needs a keyword score for candidates the keyword path never returned:
     * a conclusion found semantically still has a real BM25 score, and scoring it zero would make the
     * ranking depend on which path happened to surface it first.
     */
    public CorpusStats corpusStats(PairKey pair, List<String> terms) {
        Map<String, Object> totals = jdbc
                .sql("SELECT count(*) AS n,"
                        + " coalesce(avg(array_length(string_to_array(c.content_analyzed, ' '), 1)), 0) AS avg_len"
                        + " FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE)
                .params(pairParams(pair)).query().singleRow();
        long documentCount = ((Number) totals.get("n")).longValue();
        double averageLength = ((Number) totals.get("avg_len")).doubleValue();
        if (documentCount == 0) {
            return CorpusStats.empty();
        }

        List<Object> params = new ArrayList<>();
        params.add(terms.stream().distinct().toArray(String[]::new));
        params.addAll(pairParams(pair));
        Map<String, Long> frequencies = new HashMap<>();
        jdbc.sql("""
                SELECT t.term AS term, count(c.id) AS df
                FROM unnest(?) AS t(term)
                LEFT JOIN conclusions c
                  ON c.workspace_name = ? AND c.observer = ? AND c.observed = ?
                 AND c.deleted_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now())
                 AND to_tsvector('simple', c.content_analyzed) @@ plainto_tsquery('simple', t.term)
                GROUP BY t.term
                """).params(params).query((rs, i) -> Map.entry(rs.getString("term"), rs.getLong("df"))).list()
                .forEach(entry -> frequencies.put(entry.getKey(), entry.getValue()));
        return new CorpusStats(documentCount, averageLength, frequencies);
    }

    /**
     * Cosine similarity for a known set of ids.
     *
     * <p>The second half of the same fix: a candidate that only the keyword path found still has a
     * genuine semantic score, and the fusion formula is only comparable if every candidate is scored
     * on every signal.
     *
     * <p>Scoped and filtered like every other read here, even though today's only caller hands it a
     * candidate set that is already pair-scoped and alive. It is a public method on a class whose
     * header promises no query omits the pair, and that promise is what the next person to add a
     * caller will rely on — a scope that holds only because of who happens to call it is not one.
     */
    public Map<String, Double> semanticScores(PairKey pair, float[] q, List<String> ids) {
        if (ids.isEmpty() || q == null) {
            return Map.of();
        }
        String vector = Vectors.toLiteral(q);
        Map<String, Double> out = new LinkedHashMap<>();
        List<Object> params = new ArrayList<>();
        params.add(vector);
        params.addAll(pairParams(pair));
        params.add(ids.toArray(String[]::new));
        jdbc.sql("SELECT c.id AS id, (c.embedding <=> ?::vector) AS distance FROM conclusions c" + " WHERE "
                + PAIR_SCOPE + " AND " + ALIVE + " AND c.id = ANY (?) AND c.embedding IS NOT NULL").params(params)
                .query((rs, i) -> Map.entry(rs.getString("id"), rs.getDouble("distance"))).list()
                .forEach(entry -> out.put(entry.getKey(), Vectors.similarityFromDistance(entry.getValue())));
        return out;
    }

    // ── Write ───────────────────────────────────────────────────────────────

    /**
     * Insert, reinforce or replace.
     *
     * <p>Three stages, cheapest first. A hash lookup on an index answers most calls; normalisation
     * catches the cases where two analyzers disagreed about whitespace; only what survives both pays
     * for a vector search.
     *
     * <p>Stage 2's index is on a hash of the normalised text rather than on the text, so that a long
     * conclusion is still storable; the equality that sits beside the hash in {@link #NORM_LOOKUP} is
     * what keeps the stage exact.
     */
    @Override
    @Transactional
    public DedupOutcome upsert(ConclusionDraft draft) {
        collections.getOrCreate(draft.pair());
        DedupPolicy dedup = dedupFor(draft.pair().workspaceName());

        Optional<Conclusion> byHash = findInScope(draft, "c.content_hash = ?", draft.contentHash());
        if (byHash.isPresent()) {
            return reinforce(byHash.get(), draft, 1, Double.NaN);
        }

        Optional<Conclusion> byNorm = findInScope(draft, NORM_LOOKUP, draft.contentNorm(), draft.contentNorm());
        if (byNorm.isPresent()) {
            return reinforce(byNorm.get(), draft, 2, Double.NaN);
        }

        Optional<Neighbour> nearest = nearestInScope(draft);
        if (nearest.isPresent() && dedup.isNearDuplicate(nearest.get().distance())) {
            Conclusion existing = nearest.get().conclusion();
            double similarity = Vectors.similarityFromDistance(nearest.get().distance());
            if (dedup.newReplacesExisting(tokenize(draft.contentAnalyzed()), tokenize(existing.contentAnalyzed()))) {
                InsertOutcome outcome = insert(draft, existing.timesDerived() + 1);
                if (!outcome.inserted()) {
                    // A concurrent writer stored this same content while stage 3 was deciding. The
                    // insert became a reinforcement of their row, so the replacement never happened
                    // and the row this was going to supersede must be left alone.
                    return DedupOutcome.reinforced(outcome.id(), 3, similarity);
                }
                String newId = outcome.id();
                softDelete(draft.pair().workspaceName(), existing.id(), draft.actor(),
                        Map.of("replaced_by", newId, "similarity", similarity), EventType.REPLACE);
                Map<String, Object> detail = new LinkedHashMap<>(detailFor(draft));
                detail.put("replaces", existing.id());
                detail.put("similarity", similarity);
                detail.put("stage", 3);
                events.append(draft.pair().workspaceName(), newId, EventType.REPLACE, draft.actor(), existing.content(),
                        draft.content(), detail);
                return DedupOutcome.replaced(newId, existing.id(), similarity);
            }
            return reinforce(existing, draft, 3, similarity);
        }

        InsertOutcome outcome = insert(draft, 1);
        if (!outcome.inserted()) {
            // Lost the race past stage 1; the row exists and this call reinforced it.
            events.append(draft.pair().workspaceName(), outcome.id(), EventType.REINFORCE, draft.actor(),
                    draft.content(), draft.content(), Map.of("stage", 1, "raced", true));
            return DedupOutcome.reinforced(outcome.id(), 1, Double.NaN);
        }
        events.append(draft.pair().workspaceName(), outcome.id(), EventType.ADD, draft.actor(), null, draft.content(),
                detailFor(draft));
        return DedupOutcome.inserted(outcome.id());
    }

    private DedupOutcome reinforce(Conclusion existing, ConclusionDraft draft, int stage, double similarity) {
        jdbc.sql("UPDATE conclusions SET times_derived = times_derived + 1,"
                + " last_reinforced_at = now(), updated_at = now() WHERE id = ?").param(existing.id()).update();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stage", stage);
        if (!Double.isNaN(similarity)) {
            detail.put("similarity", similarity);
        }
        events.append(existing.pair().workspaceName(), existing.id(), EventType.REINFORCE, draft.actor(),
                existing.content(), existing.content(), detail);
        return DedupOutcome.reinforced(existing.id(), stage, similarity);
    }

    /** Level and prompt version, so a fact can be traced to the prompt that produced it. */
    private static Map<String, Object> detailFor(ConclusionDraft draft) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("level", draft.level().wire());
        if (draft.promptVersion() != null) {
            detail.put("prompt_version", draft.promptVersion());
        }
        return detail;
    }

    /**
     * @param id the row that now holds the fact
     * @param inserted false when a concurrent writer got there first and this became a reinforcement
     */
    private record InsertOutcome(String id, boolean inserted) {
    }

    /**
     * Insert, or reinforce whatever a concurrent writer inserted a moment earlier.
     *
     * <p>{@code ON CONFLICT DO UPDATE} against the scope-uniqueness index, so the read in stage 1 and
     * the write here cannot be split by another transaction. Without it the two writers both saw an
     * empty scope and both inserted.
     *
     * <p>{@code xmax = 0} is how a row reports which branch it took: Postgres leaves it zero on a
     * fresh insert and non-zero when the row was updated instead. It is the only way to tell the two
     * apart in one statement, and telling them apart is what decides whether the caller hears ADD or
     * REINFORCE.
     */
    private InsertOutcome insert(ConclusionDraft draft, int timesDerived) {
        String id = NanoId.generate();
        return jdbc.sql("""
                INSERT INTO conclusions
                  (id, workspace_name, observer, observed, session_name,
                   content, content_norm, content_analyzed, content_hash,
                   level, confidence, source_ids, message_ids,
                   times_derived, embedding, expires_at, sync_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?)
                ON CONFLICT (workspace_name, observer, observed, level,
                             coalesce(session_name, ''), content_hash)
                  WHERE deleted_at IS NULL
                DO UPDATE SET times_derived = conclusions.times_derived + 1,
                              last_reinforced_at = now(),
                              updated_at = now()
                RETURNING id, (xmax = 0) AS inserted
                """)
                .params(id, draft.pair().workspaceName(), draft.pair().observer(), draft.pair().observed(),
                        draft.sessionName(), draft.content(), draft.contentNorm(), draft.contentAnalyzed(),
                        draft.contentHash(), draft.level().wire(), draft.confidence(), Jsonb.of(draft.sourceIds()),
                        draft.messageIds().toArray(Long[]::new), timesDerived, Vectors.toLiteral(draft.embedding()),
                        draft.expiresAt() == null ? null : Timestamp.from(draft.expiresAt()),
                        draft.embedding() == null ? SyncState.PENDING.wire() : SyncState.SYNCED.wire())
                .query((rs, i) -> new InsertOutcome(rs.getString("id"), rs.getBoolean("inserted"))).single();
    }

    // ── Lookups ─────────────────────────────────────────────────────────────

    @Override
    public Optional<Conclusion> find(String workspaceName, String conclusionId) {
        return jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE c.workspace_name = ? AND c.id = ?")
                .params(workspaceName, conclusionId).query(RowMappers.CONCLUSION).optional();
    }

    /**
     * Live conclusions by id.
     *
     * <p>Soft-deleted rows are excluded, matching every other read path. Without that, a deleted fact
     * reappears through the entity provenance walk and the reasoning chain — the two places a caller
     * is most likely to read it as current, since neither response carries a deleted marker.
     *
     * <p>Callers that resolve a set of ids should compare sizes: a premise that has been deleted
     * simply will not come back, and {@link at.aimon.memory.recall.ProvenanceService} reports the gap rather
     * than letting a chain look shorter than it is.
     */
    @Override
    public List<Conclusion> byIds(String workspaceName, List<String> conclusionIds) {
        if (conclusionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS
                + " FROM conclusions c WHERE c.workspace_name = ? AND c.id = ANY (?)" + " AND c.deleted_at IS NULL")
                .params(workspaceName, conclusionIds.toArray(String[]::new)).query(RowMappers.CONCLUSION).list();
    }

    public Page<Conclusion> list(PairKey pair, Filter filter, int page, int size) {
        CompiledFilter compiled = filters.compile(filter);
        List<Object> params = new ArrayList<>(pairParams(pair));
        params.addAll(compiled.params());
        long total = jdbc.sql(
                "SELECT count(*) FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE + " AND " + compiled.sql())
                .params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<Conclusion> items = jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE
                        + " AND " + compiled.sql() + " ORDER BY c.created_at DESC, c.id LIMIT ? OFFSET ?")
                .params(pageParams).query(RowMappers.CONCLUSION).list();
        return new Page<>(items, page, size, total);
    }

    public int countByLevel(PairKey pair, ConclusionLevel level) {
        List<Object> params = new ArrayList<>(pairParams(pair));
        params.add(level.wire());
        return jdbc.sql("SELECT count(*) FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE + " AND c.level = ?")
                .params(params).query(Integer.class).single();
    }

    /** All live conclusions for a pair, newest first — the dreamer's working set. */
    public List<Conclusion> allForPair(PairKey pair, int limit) {
        List<Object> params = new ArrayList<>(pairParams(pair));
        params.add(limit);
        return jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE
                + " ORDER BY c.created_at DESC LIMIT ?").params(params).query(RowMappers.CONCLUSION).list();
    }

    // ── Mutation ────────────────────────────────────────────────────────────

    public void softDelete(String workspace, String conclusionId, Actor actor, Map<String, Object> detail,
            EventType event) {
        Optional<Conclusion> before = find(workspace, conclusionId);
        int updated = jdbc
                .sql("UPDATE conclusions SET deleted_at = now(), updated_at = now()"
                        + " WHERE workspace_name = ? AND id = ? AND deleted_at IS NULL")
                .params(workspace, conclusionId).update();
        if (updated > 0) {
            events.append(workspace, conclusionId, event, actor, before.map(Conclusion::content).orElse(null), null,
                    detail);
        }
    }

    public void restore(String workspace, String conclusionId, Actor actor) {
        int updated = jdbc
                .sql("UPDATE conclusions SET deleted_at = NULL, updated_at = now()"
                        + " WHERE workspace_name = ? AND id = ? AND deleted_at IS NOT NULL")
                .params(workspace, conclusionId).update();
        if (updated > 0) {
            Optional<Conclusion> restored = find(workspace, conclusionId);
            events.append(workspace, conclusionId, EventType.RESTORE, actor, null,
                    restored.map(Conclusion::content).orElse(null), Map.of());
        }
    }

    public void updateEmbedding(String conclusionId, float[] embedding) {
        jdbc.sql("UPDATE conclusions SET embedding = ?::vector, sync_state = 'synced', updated_at = now()"
                + " WHERE id = ?").params(Vectors.toLiteral(embedding), conclusionId).update();
    }

    public void markSyncFailed(String workspace, String conclusionId, String reason) {
        jdbc.sql("UPDATE conclusions SET sync_state = 'failed', updated_at = now() WHERE id = ?").param(conclusionId)
                .update();
        // Recorded as an event rather than only a column: a vector that never landed makes the row
        // invisible to semantic recall, and "why can I not find this" needs an answer in the log.
        //
        // Its own event type, not REINFORCE. Reusing an existing value would have avoided a migration
        // and quietly inflated every count of how often facts are re-derived with infrastructure
        // failures, which is the one number the reinf ranking signal is built on.
        events.append(workspace, conclusionId, EventType.SYNC_FAILED, Actor.RECONCILER, null, null,
                Map.of("sync_error", reason));
    }

    /** Rows whose vector never landed. The reconciler's queue. */
    public List<Conclusion> pendingEmbedding(int limit) {
        return jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c"
                + " WHERE c.deleted_at IS NULL AND (c.sync_state = 'pending' OR c.embedding IS NULL)"
                + " ORDER BY c.created_at LIMIT ?").param(limit).query(RowMappers.CONCLUSION).list();
    }

    public List<Conclusion> expired(Instant now, int limit) {
        return jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c"
                        + " WHERE c.deleted_at IS NULL AND c.expires_at IS NOT NULL AND c.expires_at <= ?"
                        + " ORDER BY c.expires_at LIMIT ?")
                .params(Timestamp.from(now), limit).query(RowMappers.CONCLUSION).list();
    }

    /**
     * Conclusions nothing has reinforced in a long time and nothing else derives from.
     *
     * <p>Reported, never deleted. Automatic forgetting that a user cannot see is indistinguishable
     * from data loss, so the system marks and lets a human decide.
     */
    public List<Conclusion> archiveCandidates(PairKey pair, Instant staleBefore, int limit) {
        List<Object> params = new ArrayList<>(pairParams(pair));
        params.add(Timestamp.from(staleBefore));
        params.add(limit);
        return jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE " + PAIR_SCOPE + " AND " + ALIVE
                        + " AND c.level = 'explicit' AND c.times_derived = 1" + " AND c.last_reinforced_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM conclusions d"
                        + "   WHERE d.workspace_name = c.workspace_name AND d.deleted_at IS NULL"
                        + "     AND d.source_ids @> to_jsonb(c.id))" + " ORDER BY c.last_reinforced_at LIMIT ?")
                .params(params).query(RowMappers.CONCLUSION).list();
    }

    /** Reasoning tree, both directions: what this rests on, and what rests on this. */
    public List<Conclusion> premisesOf(String workspace, Conclusion conclusion) {
        return byIds(workspace, conclusion.sourceIds());
    }

    public List<Conclusion> derivedFrom(String workspace, String conclusionId) {
        return jdbc.sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c"
                + " WHERE c.workspace_name = ? AND c.deleted_at IS NULL" + " AND c.source_ids @> to_jsonb(?::text)")
                .params(workspace, conclusionId).query(RowMappers.CONCLUSION).list();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Neighbour(Conclusion conclusion, double distance) {
    }

    private Optional<Conclusion> findInScope(ConclusionDraft draft, String predicate, Object... values) {
        List<Object> params = new ArrayList<>(dedupScopeParams(draft));
        // Collections.addAll rather than List.of, which rejects nulls: a predicate with a nullable
        // operand would throw out of the parameter assembly instead of reaching SQL.
        Collections.addAll(params, values);
        return jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + " FROM conclusions c WHERE " + dedupScopeSql(draft) + " AND "
                        + predicate + " ORDER BY c.created_at LIMIT 1")
                .params(params).query(RowMappers.CONCLUSION).optional();
    }

    private Optional<Neighbour> nearestInScope(ConclusionDraft draft) {
        if (draft.embedding() == null) {
            return Optional.empty();
        }
        String vector = Vectors.toLiteral(draft.embedding());
        List<Object> params = new ArrayList<>();
        params.add(vector);
        params.addAll(dedupScopeParams(draft));
        params.add(vector);
        return jdbc
                .sql("SELECT " + Sql.CONCLUSION_COLUMNS + ", (c.embedding <=> ?::vector) AS distance"
                        + " FROM conclusions c WHERE " + dedupScopeSql(draft) + " AND c.embedding IS NOT NULL"
                        + " ORDER BY c.embedding <=> ?::vector LIMIT 1")
                .params(params)
                .query((rs, i) -> new Neighbour(RowMappers.CONCLUSION.mapRow(rs, i), rs.getDouble("distance")))
                .optional();
    }

    /**
     * Dedup never crosses a level, and for explicit conclusions never crosses a session.
     *
     * <p>The same sentence said in two sessions is two facts with two provenances; collapsing them
     * would make the earlier session's messages unreachable from the surviving row.
     */
    private String dedupScopeSql(ConclusionDraft draft) {
        String scope = PAIR_SCOPE + " AND " + ALIVE + " AND c.level = ?";
        return draft.level() == ConclusionLevel.EXPLICIT ? scope + " AND c.session_name = ?" : scope;
    }

    private List<Object> dedupScopeParams(ConclusionDraft draft) {
        List<Object> params = new ArrayList<>(pairParams(draft.pair()));
        params.add(draft.level().wire());
        if (draft.level() == ConclusionLevel.EXPLICIT) {
            params.add(draft.sessionName());
        }
        return params;
    }

    private static List<Object> pairParams(PairKey pair) {
        return List.of(pair.workspaceName(), pair.observer(), pair.observed());
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static List<String> tokenize(String analyzed) {
        if (analyzed == null || analyzed.isBlank()) {
            return List.of();
        }
        return List.of(analyzed.trim().split("\\s+"));
    }
}
