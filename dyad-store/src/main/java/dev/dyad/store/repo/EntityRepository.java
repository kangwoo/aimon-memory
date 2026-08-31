package dev.dyad.store.repo;

import dev.dyad.core.id.NanoId;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.EntityMatch;
import dev.dyad.core.model.EntityRef;
import dev.dyad.core.spi.EntityStore;
import dev.dyad.store.Vectors;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    private static final org.springframework.jdbc.core.RowMapper<EntityRef> ENTITY =
            (rs, i) ->
                    new EntityRef(
                            rs.getString("id"),
                            rs.getString("workspace_name"),
                            rs.getString("name_norm"),
                            rs.getString("name_display"),
                            rs.getString("kind"));

    /** Lowercase and collapse internal whitespace. Mirrors what {@code name_norm} is unique on. */
    public static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Nearest entity nodes, with live link counts.
     *
     * <p>The count drives {@code countWeight}, so it has to exclude deleted conclusions. Counting
     * them would make an entity look overused and permanently deflate its boost after any churn —
     * silently, and in the direction of returning worse results.
     */
    @Override
    public List<EntityMatch> match(String workspace, float[] q, int topK) {
        if (q == null) {
            return List.of();
        }
        String vector = Vectors.toLiteral(q);
        return jdbc.sql(
                        """
                        SELECT e.id, e.workspace_name, e.name_norm, e.name_display, e.kind,
                               (e.embedding <=> ?::vector) AS distance,
                               (SELECT count(*) FROM entity_links l
                                  JOIN conclusions c ON c.id = l.conclusion_id AND c.deleted_at IS NULL
                                WHERE l.entity_id = e.id) AS link_count
                        FROM entities e
                        WHERE e.workspace_name = ? AND e.embedding IS NOT NULL
                        ORDER BY e.embedding <=> ?::vector
                        LIMIT ?
                        """)
                .params(vector, workspace, vector, topK)
                .query(
                        (rs, i) ->
                                new EntityMatch(
                                        ENTITY.mapRow(rs, i),
                                        Vectors.similarityFromDistance(rs.getDouble("distance")),
                                        rs.getInt("link_count")))
                .list();
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
            List<EntityMatch> nearest = match(workspace, embedding, 1);
            if (!nearest.isEmpty() && nearest.get(0).similarity() >= MERGE_SIMILARITY) {
                return nearest.get(0).entity();
            }
        }
        String id = NanoId.generate();
        jdbc.sql(
                        """
                        INSERT INTO entities (id, workspace_name, name_norm, name_display, kind, embedding)
                        VALUES (?, ?, ?, ?, ?, ?::vector)
                        ON CONFLICT (workspace_name, name_norm) DO NOTHING
                        """)
                .params(id, workspace, norm, displayName.strip(), kind, Vectors.toLiteral(embedding))
                .update();
        return findByNorm(workspace, norm).orElseThrow();
    }

    public Optional<EntityRef> findByNorm(String workspace, String nameNorm) {
        return jdbc.sql("SELECT * FROM entities WHERE workspace_name = ? AND name_norm = ?")
                .params(workspace, nameNorm)
                .query(ENTITY)
                .optional();
    }

    @Override
    public void link(String workspace, String entityId, String conclusionId, PairKey pair) {
        jdbc.sql(
                        """
                        INSERT INTO entity_links (workspace_name, entity_id, conclusion_id, observer, observed)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (workspace_name, entity_id, conclusion_id) DO NOTHING
                        """)
                .params(workspace, entityId, conclusionId, pair.observer(), pair.observed())
                .update();
    }

    /** Conclusions an entity is attached to, within one pair. The provenance walk starts here. */
    public List<String> linkedConclusionIds(String workspace, String entityId, PairKey pair) {
        return jdbc.sql(
                        "SELECT conclusion_id FROM entity_links"
                                + " WHERE workspace_name = ? AND entity_id = ? AND observer = ? AND observed = ?"
                                + " ORDER BY created_at")
                .params(workspace, entityId, pair.observer(), pair.observed())
                .query(String.class)
                .list();
    }

    /** Which of these entities are attached to this conclusion, for the {@code ent} signal's report. */
    public List<String> entityIdsFor(String workspace, String conclusionId) {
        return jdbc.sql(
                        "SELECT entity_id FROM entity_links WHERE workspace_name = ? AND conclusion_id = ?")
                .params(workspace, conclusionId)
                .query(String.class)
                .list();
    }

    public List<EntityRef> entitiesFor(String workspace, String conclusionId) {
        return jdbc.sql(
                        "SELECT e.* FROM entities e JOIN entity_links l ON l.entity_id = e.id"
                                + " WHERE l.workspace_name = ? AND l.conclusion_id = ? ORDER BY e.name_norm")
                .params(workspace, conclusionId)
                .query(ENTITY)
                .list();
    }

    /** Which of these entities touch which of these conclusions, inside one pair. */
    public List<EntityLink> linksAmong(
            String workspace, PairKey pair, List<String> entityIds, List<String> conclusionIds) {
        if (entityIds.isEmpty() || conclusionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT entity_id, conclusion_id FROM entity_links"
                                + " WHERE workspace_name = ? AND observer = ? AND observed = ?"
                                + " AND entity_id = ANY (?) AND conclusion_id = ANY (?)")
                .params(
                        workspace,
                        pair.observer(),
                        pair.observed(),
                        entityIds.toArray(String[]::new),
                        conclusionIds.toArray(String[]::new))
                .query((rs, i) -> new EntityLink(rs.getString("entity_id"), rs.getString("conclusion_id")))
                .list();
    }

    public record EntityLink(String entityId, String conclusionId) {}

    public void unlinkConclusion(String workspace, String conclusionId) {
        jdbc.sql("DELETE FROM entity_links WHERE workspace_name = ? AND conclusion_id = ?")
                .params(workspace, conclusionId)
                .update();
    }

    /**
     * Drop nodes nothing points at any more.
     *
     * <p>Without this the node table only ever grows, and every orphan keeps degrading the count
     * weight of the entities that are still real.
     */
    public int deleteOrphans(String workspace) {
        return jdbc.sql(
                        "DELETE FROM entities e WHERE e.workspace_name = ?"
                                + " AND NOT EXISTS (SELECT 1 FROM entity_links l WHERE l.entity_id = e.id)")
                .param(workspace)
                .update();
    }

    public Optional<EntityRef> findById(String workspace, String entityId) {
        return jdbc.sql("SELECT * FROM entities WHERE workspace_name = ? AND id = ?")
                .params(workspace, entityId)
                .query(ENTITY)
                .optional();
    }

    /** Entities whose vector is missing — created before an embedder was available, or re-indexed. */
    public List<EntityRef> withoutEmbedding(String workspace, int limit) {
        return jdbc.sql(
                        "SELECT * FROM entities WHERE workspace_name = ? AND embedding IS NULL"
                                + " ORDER BY created_at LIMIT ?")
                .params(workspace, limit)
                .query(ENTITY)
                .list();
    }

    public void updateEmbedding(String entityId, float[] embedding) {
        jdbc.sql("UPDATE entities SET embedding = ?::vector WHERE id = ?")
                .params(Vectors.toLiteral(embedding), entityId)
                .update();
    }
}
