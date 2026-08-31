package dev.dyad.store.repo;

import dev.dyad.core.model.Actor;
import dev.dyad.core.model.ConclusionEvent;
import dev.dyad.core.model.EventType;
import dev.dyad.core.spi.EventLog;
import dev.dyad.store.Jsonb;
import dev.dyad.store.RowMappers;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventLogRepository implements EventLog {

    private final JdbcClient jdbc;

    public EventLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(
            String workspaceName,
            String conclusionId,
            EventType event,
            Actor actor,
            String beforeContent,
            String afterContent,
            Map<String, Object> detail) {
        jdbc.sql(
                        """
                        INSERT INTO conclusion_events
                          (workspace_name, conclusion_id, event, actor, before_content, after_content, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        workspaceName,
                        conclusionId,
                        event.wire(),
                        actor.wire(),
                        beforeContent,
                        afterContent,
                        Jsonb.of(detail == null ? Map.of() : detail))
                .update();
    }

    @Override
    public List<ConclusionEvent> history(String workspaceName, String conclusionId, int limit) {
        return jdbc.sql(
                        "SELECT * FROM conclusion_events WHERE workspace_name = ? AND conclusion_id = ?"
                                + " ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(workspaceName, conclusionId, limit)
                .query(RowMappers.EVENT)
                .list();
    }

    public List<ConclusionEvent> recent(String workspaceName, int limit) {
        return jdbc.sql(
                        "SELECT * FROM conclusion_events WHERE workspace_name = ?"
                                + " ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(workspaceName, limit)
                .query(RowMappers.EVENT)
                .list();
    }
}
