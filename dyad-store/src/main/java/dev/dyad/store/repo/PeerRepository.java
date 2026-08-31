package dev.dyad.store.repo;

import dev.dyad.core.model.Page;
import dev.dyad.core.model.Peer;
import dev.dyad.store.Jsonb;
import dev.dyad.store.RowMappers;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PeerRepository {

    private final JdbcClient jdbc;

    public PeerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Peer getOrCreate(String workspace, String name, Map<String, Object> metadata, Map<String, Object> configuration) {
        jdbc.sql(
                        """
                        INSERT INTO peers (workspace_name, name, metadata, configuration)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (workspace_name, name) DO NOTHING
                        """)
                .params(workspace, name, Jsonb.of(metadata), Jsonb.of(configuration))
                .update();
        return find(workspace, name).orElseThrow();
    }

    public Optional<Peer> find(String workspace, String name) {
        return jdbc.sql("SELECT * FROM peers WHERE workspace_name = ? AND name = ?")
                .params(workspace, name)
                .query(RowMappers.PEER)
                .optional();
    }

    public void updateConfiguration(String workspace, String name, Map<String, Object> configuration) {
        jdbc.sql("UPDATE peers SET configuration = ? WHERE workspace_name = ? AND name = ?")
                .params(Jsonb.of(configuration), workspace, name)
                .update();
    }

    public Page<Peer> list(String workspace, int page, int size) {
        long total =
                jdbc.sql("SELECT count(*) FROM peers WHERE workspace_name = ?")
                        .param(workspace)
                        .query(Long.class)
                        .single();
        var items =
                jdbc.sql("SELECT * FROM peers WHERE workspace_name = ? ORDER BY name LIMIT ? OFFSET ?")
                        .params(workspace, size, (long) page * size)
                        .query(RowMappers.PEER)
                        .list();
        return new Page<>(items, page, size, total);
    }
}
