package dev.dyad.store.repo;

import dev.dyad.core.id.NanoId;
import dev.dyad.core.key.PairKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Dream runs.
 *
 * <p>The "one in flight per pair" rule is enforced by a partial unique index, not by checking first
 * and inserting second. Two schedulers racing on the same pair is the normal case — a manual trigger
 * arriving while the automatic threshold fires — and a check-then-insert loses that race quietly.
 */
@Repository
public class DreamRepository {

    private final JdbcClient jdbc;

    public DreamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum DreamType {
        CONSOLIDATE,
        CARD_REFRESH;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Dream(
            String id,
            String workspaceName,
            String observer,
            String observed,
            String dreamType,
            String status,
            int conclusionsAtStart,
            int produced,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {}

    private static final org.springframework.jdbc.core.RowMapper<Dream> MAPPER =
            (rs, i) ->
                    new Dream(
                            rs.getString("id"),
                            rs.getString("workspace_name"),
                            rs.getString("observer"),
                            rs.getString("observed"),
                            rs.getString("dream_type"),
                            rs.getString("status"),
                            rs.getInt("conclusions_at_start"),
                            rs.getInt("produced"),
                            rs.getString("error"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                            rs.getTimestamp("completed_at") == null
                                    ? null
                                    : rs.getTimestamp("completed_at").toInstant());

    /** @return the new dream, or empty when one is already in flight for this pair. */
    public Optional<Dream> schedule(PairKey pair, DreamType type, int conclusionsAtStart) {
        String id = NanoId.generate();
        Optional<String> inserted =
                jdbc.sql(
                                """
                                INSERT INTO dreams
                                  (id, workspace_name, observer, observed, dream_type, status, conclusions_at_start)
                                VALUES (?, ?, ?, ?, ?, 'pending', ?)
                                ON CONFLICT (workspace_name, observer, observed)
                                  WHERE status IN ('pending', 'running')
                                  DO NOTHING
                                RETURNING id
                                """)
                        .params(
                                id,
                                pair.workspaceName(),
                                pair.observer(),
                                pair.observed(),
                                type.wire(),
                                conclusionsAtStart)
                        .query(String.class)
                        .optional();
        return inserted.flatMap(this::find);
    }

    public Optional<Dream> find(String id) {
        return jdbc.sql("SELECT * FROM dreams WHERE id = ?").param(id).query(MAPPER).optional();
    }

    public void start(String id) {
        jdbc.sql("UPDATE dreams SET status = 'running', started_at = now() WHERE id = ?").param(id).update();
    }

    public void complete(String id, int produced) {
        jdbc.sql(
                        "UPDATE dreams SET status = 'completed', produced = ?, completed_at = now()"
                                + " WHERE id = ?")
                .params(produced, id)
                .update();
    }

    public void fail(String id, String error) {
        jdbc.sql("UPDATE dreams SET status = 'failed', error = ?, completed_at = now() WHERE id = ?")
                .params(error, id)
                .update();
    }

    public List<Dream> pending(int limit) {
        return jdbc.sql("SELECT * FROM dreams WHERE status = 'pending' ORDER BY created_at LIMIT ?")
                .param(limit)
                .query(MAPPER)
                .list();
    }

    public List<Dream> forPair(PairKey pair, int limit) {
        return jdbc.sql(
                        "SELECT * FROM dreams WHERE workspace_name = ? AND observer = ? AND observed = ?"
                                + " ORDER BY created_at DESC LIMIT ?")
                .params(pair.workspaceName(), pair.observer(), pair.observed(), limit)
                .query(MAPPER)
                .list();
    }
}
