package at.aimon.memory.store.repo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import at.aimon.memory.core.id.NanoId;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EntityMatch;
import at.aimon.memory.core.model.EntityRef;
import at.aimon.memory.core.spi.EntityStore;
import at.aimon.memory.store.Vectors;

/**
 * Entity nodes and their pair-scoped edges.
 *
 * <p>Resolution is exact-match first, then a single semantic candidate above a deliberately high
 * threshold. A loose threshold here is how "Seoul" and "Seoul National University" become one node,
 * and once two entities merge there is no signal left to split them apart again.
 */
@Repository
public class EntityRepository implements EntityStore {

    /** Only a near-certain vector match counts as the same entity. Below this, create a new node. */
    public static final double MERGE_SIMILARITY = 0.95;

    private final JdbcClient jdbc;

    public EntityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final org.springframework.jdbc.core.RowMapper<EntityRef> ENTITY = (rs, i) -> new EntityRef(
            rs.getString("id"), rs.getString("workspace_name"), rs.getString("name_norm"), rs.getString("name_display"),
            rs.getString("kind"));

    /** Lowercase and collapse internal whitespace. Mirrors what {@code name_norm} is unique on. */
    public static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * The boost query, as a constant so the index gate can EXPLAIN the statement that ships.
     *
     * <p>It EXPLAINed a hand-written single-table lookup instead, which stopped describing this the
     * moment the candidate set became a join — the assertion kept passing for a query no caller
     * issues, which is the one failure an index-usage gate cannot afford.
     */
    static final String MATCH_SQL = """
            SELECT e.id, e.workspace_name, e.name_norm, e.name_display, e.kind,
                   (e.embedding <=> ?::vector) AS distance,
                   count(*) AS link_count
            FROM entities e
            JOIN entity_links l
              ON l.entity_id = e.id
             AND l.workspace_name = ? AND l.observer = ? AND l.observed = ?
            JOIN conclusions c ON c.id = l.conclusion_id AND c.deleted_at IS NULL
            WHERE e.workspace_name = ? AND e.embedding IS NOT NULL
            GROUP BY e.id
            ORDER BY e.embedding <=> ?::vector
            LIMIT ?
            """;

    /**
     * Nearest entity nodes <em>that this pair actually has edges to</em>, with live link counts.
     *
     * <p>The count drives {@code countWeight}, so it has to exclude deleted conclusions. Counting them
     * would make an entity look overused and permanently deflate its boost after any churn — silently,
     * and in the direction of returning worse results.
     *
     * <p>And it has to be counted inside the pair, which is what {@code entity_links} carries the
     * observer and observed for. Counting workspace-wide meant a shared name paid for every other
     * tenant's links: "Seoul" attached to five conclusions in the querying pair and four hundred across
     * the workspace scored 0.006 instead of 0.984, so the {@code ent} signal collapsed to nothing for
     * exactly the entities it exists to reward, and got worse as the deployment grew.
     *
     * <p><b>The candidate set is the pair's own entities, not the workspace's.</b> Nodes are shared
     * across a workspace by design, so a top-k taken over all of them fills up with entities the
     * querying pair has no edge to — which contribute nothing, since {@link
     * at.aimon.memory.recall.signal.EntityBoost} works from edges — while the pair's own entities fall off
     * the end. That is the same failure as the workspace-wide count above, one step earlier in the
     * query, and it also gets worse as the deployment grows. Nothing is lost by excluding them: an
     * entity with no edge in the pair could never have produced a boost.
     *
     * <p>The cost is that the vector index no longer serves the ordering — the join has to be
     * evaluated first, so this is a scan over the pair's entities rather than an index walk over the
     * workspace's. That is the right trade at the scale a pair operates at, and it is bounded by the
     * pair rather than by the deployment.
     */
    @Override
    public List<EntityMatch> match(PairKey pair, float[] q, int topK) {
        if (q == null) {
            return List.of();
        }
        String vector = Vectors.toLiteral(q);
        return jdbc.sql(MATCH_SQL)
                .params(vector, pair.workspaceName(), pair.observer(), pair.observed(), pair.workspaceName(), vector,
                        topK)
                .query((rs, i) -> new EntityMatch(ENTITY.mapRow(rs, i),
                        Vectors.similarityFromDistance(rs.getDouble("distance")), rs.getInt("link_count")))
                .list();
    }

    /**
     * Nearest node in the workspace, for deciding whether a name is one we already have.
     *
     * <p>Separate from {@link #match} because it asks a genuinely different question: node identity is
     * workspace-scoped, and merging is a decision about the node, not about any pair's view of it. It
     * also wants no link count, which is the part {@code match} has to scope.
     */
    private Optional<Nearest> nearestInWorkspace(String workspace, float[] q) {
        if (q == null) {
            return Optional.empty();
        }
        String vector = Vectors.toLiteral(q);
        return jdbc.sql("""
                SELECT e.id, e.workspace_name, e.name_norm, e.name_display, e.kind,
                       (e.embedding <=> ?::vector) AS distance
                FROM entities e
                WHERE e.workspace_name = ? AND e.embedding IS NOT NULL
                ORDER BY e.embedding <=> ?::vector
                LIMIT 1
                """).params(vector, workspace, vector).query(EntityRepository::nearest).optional();
    }

    private static Nearest nearest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Nearest(ENTITY.mapRow(rs, rowNum), Vectors.similarityFromDistance(rs.getDouble("distance")));
    }

    private record Nearest(EntityRef entity, double similarity) {
    }

    /**
     * Find or create the node for a name.
     *
     * <p>The exact-match branch is not just an optimisation: it means an entity written a thousand
     * times is embedded once, which is the whole reason nodes are workspace-scoped.
     */
    public EntityRef upsert(String workspace, String displayName, String kind, float[] embedding) {
        String norm = normalize(displayName);
        Optional<EntityRef> exact = findByNorm(workspace, norm);
        if (exact.isPresent()) {
            return exact.get();
        }
        if (embedding != null) {
            Optional<Nearest> nearest = nearestInWorkspace(workspace, embedding);
            if (nearest.isPresent() && nearest.get().similarity() >= MERGE_SIMILARITY) {
                return nearest.get().entity();
            }
        }
        String id = NanoId.generate();
        jdbc.sql("""
                INSERT INTO entities (id, workspace_name, name_norm, name_display, kind, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                ON CONFLICT (workspace_name, name_norm) DO NOTHING
                """).params(id, workspace, norm, displayName.strip(), kind, Vectors.toLiteral(embedding)).update();
        return findByNorm(workspace, norm).orElseThrow();
    }

    public Optional<EntityRef> findByNorm(String workspace, String nameNorm) {
        return jdbc.sql("SELECT * FROM entities WHERE workspace_name = ? AND name_norm = ?").params(workspace, nameNorm)
                .query(ENTITY).optional();
    }

    @Override
    public void link(String workspace, String entityId, String conclusionId, PairKey pair) {
        jdbc.sql("""
                INSERT INTO entity_links (workspace_name, entity_id, conclusion_id, observer, observed)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (workspace_name, entity_id, conclusion_id) DO NOTHING
                """).params(workspace, entityId, conclusionId, pair.observer(), pair.observed()).update();
    }

    /** Conclusions an entity is attached to, within one pair. The provenance walk starts here. */
    public List<String> linkedConclusionIds(String workspace, String entityId, PairKey pair) {
        return jdbc
                .sql("SELECT conclusion_id FROM entity_links"
                        + " WHERE workspace_name = ? AND entity_id = ? AND observer = ? AND observed = ?"
                        + " ORDER BY created_at")
                .params(workspace, entityId, pair.observer(), pair.observed()).query(String.class).list();
    }

    /** Which of these entities are attached to this conclusion, for the {@code ent} signal's report. */
    public List<String> entityIdsFor(String workspace, String conclusionId) {
        return jdbc.sql("SELECT entity_id FROM entity_links WHERE workspace_name = ? AND conclusion_id = ?")
                .params(workspace, conclusionId).query(String.class).list();
    }

    public List<EntityRef> entitiesFor(String workspace, String conclusionId) {
        return jdbc
                .sql("SELECT e.* FROM entities e JOIN entity_links l ON l.entity_id = e.id"
                        + " WHERE l.workspace_name = ? AND l.conclusion_id = ? ORDER BY e.name_norm")
                .params(workspace, conclusionId).query(ENTITY).list();
    }

    /** Which of these entities touch which of these conclusions, inside one pair. */
    public List<EntityLink> linksAmong(String workspace, PairKey pair, List<String> entityIds,
            List<String> conclusionIds) {
        if (entityIds.isEmpty() || conclusionIds.isEmpty()) {
            return List.of();
        }
        return jdbc
                .sql("SELECT entity_id, conclusion_id FROM entity_links"
                        + " WHERE workspace_name = ? AND observer = ? AND observed = ?"
                        + " AND entity_id = ANY (?) AND conclusion_id = ANY (?)")
                .params(workspace, pair.observer(), pair.observed(), entityIds.toArray(String[]::new),
                        conclusionIds.toArray(String[]::new))
                .query((rs, i) -> new EntityLink(rs.getString("entity_id"), rs.getString("conclusion_id"))).list();
    }

    public record EntityLink(String entityId, String conclusionId) {
    }

    public void unlinkConclusion(String workspace, String conclusionId) {
        jdbc.sql("DELETE FROM entity_links WHERE workspace_name = ? AND conclusion_id = ?")
                .params(workspace, conclusionId).update();
    }

    /**
     * Drop nodes nothing points at any more.
     *
     * <p>Without this the node table only ever grows, and every orphan keeps degrading the count
     * weight of the entities that are still real.
     */
    public int deleteOrphans(String workspace) {
        return jdbc
                .sql("DELETE FROM entities e WHERE e.workspace_name = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM entity_links l WHERE l.entity_id = e.id)")
                .param(workspace).update();
    }

    public Optional<EntityRef> findById(String workspace, String entityId) {
        return jdbc.sql("SELECT * FROM entities WHERE workspace_name = ? AND id = ?").params(workspace, entityId)
                .query(ENTITY).optional();
    }

    /** Entities whose vector is missing — created before an embedder was available, or re-indexed. */
    public List<EntityRef> withoutEmbedding(String workspace, int limit) {
        return jdbc.sql("SELECT * FROM entities WHERE workspace_name = ? AND embedding IS NULL"
                + " ORDER BY created_at LIMIT ?").params(workspace, limit).query(ENTITY).list();
    }

    public void updateEmbedding(String entityId, float[] embedding) {
        jdbc.sql("UPDATE entities SET embedding = ?::vector WHERE id = ?")
                .params(Vectors.toLiteral(embedding), entityId).update();
    }
}
