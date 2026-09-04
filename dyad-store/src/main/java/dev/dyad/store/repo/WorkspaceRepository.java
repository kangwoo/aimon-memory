package dev.dyad.store.repo;

import dev.dyad.core.model.Page;
import dev.dyad.core.model.Workspace;
import dev.dyad.store.Jsonb;
import dev.dyad.store.RowMappers;
import dev.dyad.store.WorkspaceSettingsService;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {

    private final JdbcClient jdbc;

    public WorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent create.
     *
     * <p>{@code ON CONFLICT DO NOTHING} followed by a read, rather than a check-then-insert. Two
     * requests naming the same new workspace arrive together far more often than the code path
     * suggests — every client that lazily creates its workspace on first use does exactly this.
     */
    public Workspace getOrCreate(String name, Map<String, Object> metadata, Map<String, Object> configuration) {
        WorkspaceSettingsService.validate(configuration);
        jdbc.sql(
                        """
                        INSERT INTO workspaces (name, metadata, configuration)
                        VALUES (?, ?, ?)
                        ON CONFLICT (name) DO NOTHING
                        """)
                .params(name, Jsonb.of(metadata), Jsonb.of(configuration))
                .update();
        return find(name).orElseThrow();
    }

    public Optional<Workspace> find(String name) {
        return jdbc.sql("SELECT * FROM workspaces WHERE name = ?")
                .param(name)
                .query(RowMappers.WORKSPACE)
                .optional();
    }

    /**
     * Replace the tuning configuration, validated first.
     *
     * <p>Checked here rather than at the route, because the column has more than one way in and the
     * check kept being attached to whichever one was noticed last. A configuration that reaches the
     * table unchecked is a tuning session that produces default rankings with nothing anywhere to say
     * why — the failure {@link dev.dyad.core.config.ConfigurationException} exists to prevent.
     */
    public void updateConfiguration(String name, Map<String, Object> configuration) {
        WorkspaceSettingsService.validate(configuration);
        jdbc.sql("UPDATE workspaces SET configuration = ? WHERE name = ?")
                .params(Jsonb.of(configuration), name)
                .update();
    }

    public Page<Workspace> list(int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM workspaces").query(Long.class).single();
        var items =
                jdbc.sql("SELECT * FROM workspaces ORDER BY created_at DESC, name LIMIT ? OFFSET ?")
                        .params(size, (long) page * size)
                        .query(RowMappers.WORKSPACE)
                        .list();
        return new Page<>(items, page, size, total);
    }
}
