package dev.dyad.store.repo;

import dev.dyad.core.model.Page;
import dev.dyad.core.model.Session;
import dev.dyad.store.Jsonb;
import dev.dyad.store.RowMappers;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Session getOrCreate(String workspace, String name, Map<String, Object> metadata, Map<String, Object> configuration) {
        jdbc.sql(
                        """
                        INSERT INTO sessions (workspace_name, name, metadata, configuration)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (workspace_name, name) DO NOTHING
                        """)
                .params(workspace, name, Jsonb.of(metadata), Jsonb.of(configuration))
                .update();
        return find(workspace, name).orElseThrow();
    }

    public Optional<Session> find(String workspace, String name) {
        return jdbc.sql("SELECT * FROM sessions WHERE workspace_name = ? AND name = ?")
                .params(workspace, name)
                .query(RowMappers.SESSION)
                .optional();
    }

    /**
     * Allocate the next {@code seq_in_session}.
     *
     * <p>A counter column bumped inside the transaction, not a sequence and not {@code max(seq)+1}.
     * A sequence would leave gaps on rollback and the summariser walks this ordering expecting none;
     * {@code max+1} races two concurrent writers into the same number and one of them violates the
     * unique constraint. The {@code UPDATE … RETURNING} takes the session row lock, which serialises
     * exactly the writers that need serialising and nobody else.
     */
    public long nextSequence(String workspace, String session, int count) {
        Long end =
                jdbc.sql(
                                """
                                UPDATE sessions SET message_seq = message_seq + ?
                                WHERE workspace_name = ? AND name = ?
                                RETURNING message_seq
                                """)
                        .params(count, workspace, session)
                        .query(Long.class)
                        .single();
        return end - count + 1;
    }

    public void updateInternalMetadata(String workspace, String name, Map<String, Object> internalMetadata) {
        jdbc.sql("UPDATE sessions SET internal_metadata = ? WHERE workspace_name = ? AND name = ?")
                .params(Jsonb.of(internalMetadata), workspace, name)
                .update();
    }

    public void setActive(String workspace, String name, boolean active) {
        jdbc.sql("UPDATE sessions SET is_active = ? WHERE workspace_name = ? AND name = ?")
                .params(active, workspace, name)
                .update();
    }

    public Page<Session> list(String workspace, int page, int size) {
        long total =
                jdbc.sql("SELECT count(*) FROM sessions WHERE workspace_name = ?")
                        .param(workspace)
                        .query(Long.class)
                        .single();
        var items =
                jdbc.sql(
                                "SELECT * FROM sessions WHERE workspace_name = ?"
                                        + " ORDER BY created_at DESC, name LIMIT ? OFFSET ?")
                        .params(workspace, size, (long) page * size)
                        .query(RowMappers.SESSION)
                        .list();
        return new Page<>(items, page, size, total);
    }
}
