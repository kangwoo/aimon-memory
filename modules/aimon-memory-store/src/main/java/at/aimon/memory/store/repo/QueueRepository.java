package at.aimon.memory.store.repo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import at.aimon.memory.core.config.BatchSettings;
import at.aimon.memory.core.key.TaskType;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.store.Jsonb;

/**
 * The work queue and its claim table.
 *
 * <p>Claiming is an insert against a primary key, not a row lock. A worker learns that a key is
 * taken from a unique-constraint conflict, which means no transaction stays open across an LLM call
 * that can take thirty seconds — the failure mode that turns a slow model into a stalled database.
 */
@Repository
public class QueueRepository {

    private final JdbcClient jdbc;

    public QueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record QueueItem(long id, String workspaceName, String sessionName, String workUnitKey, TaskType taskType,
            Map<String, Object> payload, int tokenCount, int attempts, Instant createdAt) {
    }

    public record ReadyUnit(String workUnitKey, long tokens, int items, Instant oldest, Instant newest) {
    }

    public long enqueue(WorkUnitKey key, Map<String, Object> payload, int tokenCount) {
        return jdbc.sql("""
                INSERT INTO queue
                  (workspace_name, session_name, work_unit_key, task_type, payload, token_count)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """).params(key.workspaceName(), key.sessionName(), key.encode(), key.taskType().wire(),
                Jsonb.of(payload), tokenCount).query(Long.class).single();
    }

    /**
     * Work units eligible under any of the three gates.
     *
     * <p>The third — no new message for a few seconds — is what makes conclusions visible inside a
     * conversation instead of up to half an hour later. People speak in bursts and then pause; the
     * pause is the natural flush point. Under load the token gate fires first, so batching still pays.
     */
    public List<ReadyUnit> ready(BatchSettings settings, int limit) {
        return ready(null, settings, limit);
    }

    /**
     * @param workspace restricts the scan to one workspace, so each can be gated by its own settings;
     *     null scans everything with the settings given
     */
    public List<ReadyUnit> ready(String workspace, BatchSettings settings, int limit) {
        return jdbc.sql("""
                SELECT q.work_unit_key,
                       sum(q.token_count)  AS tokens,
                       count(*)            AS items,
                       min(q.created_at)   AS oldest,
                       max(q.created_at)   AS newest
                FROM queue q
                WHERE q.processed = FALSE
                  -- The cast is required: Postgres cannot infer a parameter's type from
                  -- `? IS NULL` alone, and rejects the statement rather than guessing.
                  AND (CAST(? AS text) IS NULL OR q.workspace_name = ?)
                  AND NOT EXISTS (SELECT 1 FROM work_unit_claims c
                                  WHERE c.work_unit_key = q.work_unit_key AND c.expires_at > now())
                GROUP BY q.work_unit_key
                HAVING sum(q.token_count) >= ?
                    OR min(q.created_at) <= now() - (? * interval '1 second')
                    OR max(q.created_at) <= now() - (? * interval '1 second')
                ORDER BY min(q.created_at)
                LIMIT ?
                """)
                .params(workspace, workspace, settings.tokenThreshold(), settings.maxAge().toSeconds(),
                        settings.idleFlush().toSeconds(), limit)
                .query((rs, i) -> new ReadyUnit(rs.getString("work_unit_key"), rs.getLong("tokens"), rs.getInt("items"),
                        rs.getTimestamp("oldest").toInstant(), rs.getTimestamp("newest").toInstant()))
                .list();
    }

    /** @return true when this worker now owns the key. */
    public boolean claim(String workUnitKey, String workerId, Duration ttl) {
        return jdbc.sql("""
                INSERT INTO work_unit_claims (work_unit_key, worker_id, expires_at)
                VALUES (?, ?, now() + (? * interval '1 second'))
                ON CONFLICT (work_unit_key) DO NOTHING
                RETURNING work_unit_key
                """).params(workUnitKey, workerId, ttl.toSeconds()).query(String.class).optional().isPresent();
    }

    public void release(String workUnitKey, String workerId) {
        jdbc.sql("DELETE FROM work_unit_claims WHERE work_unit_key = ? AND worker_id = ?").params(workUnitKey, workerId)
                .update();
    }

    public void extendClaim(String workUnitKey, String workerId, Duration ttl) {
        jdbc.sql("UPDATE work_unit_claims SET expires_at = now() + (? * interval '1 second')"
                + " WHERE work_unit_key = ? AND worker_id = ?").params(ttl.toSeconds(), workUnitKey, workerId).update();
    }

    /** A worker that died mid-batch leaves its claim behind; the TTL is what lets anyone else proceed. */
    public int releaseExpiredClaims() {
        return jdbc.sql("DELETE FROM work_unit_claims WHERE expires_at <= now()").update();
    }

    public List<QueueItem> pending(String workUnitKey, int limit) {
        return jdbc.sql("SELECT * FROM queue WHERE work_unit_key = ? AND processed = FALSE" + " ORDER BY id LIMIT ?")
                .params(workUnitKey, limit)
                .query((rs, i) -> new QueueItem(rs.getLong("id"), rs.getString("workspace_name"),
                        rs.getString("session_name"), rs.getString("work_unit_key"),
                        TaskType.fromWire(rs.getString("task_type")), Jsonb.toMap(rs.getString("payload")),
                        rs.getInt("token_count"), rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void markProcessed(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE queue SET processed = TRUE, processed_at = now() WHERE id = ANY (?)")
                .param(ids.toArray(Long[]::new)).update();
    }

    /**
     * Record a failure without consuming the items.
     *
     * <p>Attempts are counted so a batch that fails forever can be quarantined rather than blocking
     * its work unit indefinitely — a poison message on a serialised key stops everything behind it.
     */
    public void recordFailure(List<Long> ids, String error) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE queue SET attempts = attempts + 1, last_error = ? WHERE id = ANY (?)")
                .params(error, ids.toArray(Long[]::new)).update();
    }

    public void quarantine(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE queue SET processed = TRUE, processed_at = now() WHERE id = ANY (?)")
                .param(ids.toArray(Long[]::new)).update();
    }

    public int deleteProcessedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM queue WHERE processed = TRUE AND processed_at < ?")
                .param(java.sql.Timestamp.from(cutoff)).update();
    }

    /**
     * Workspaces with unclaimed work waiting.
     *
     * <p>The poll loop asks this first so that each workspace can be gated by its own batch settings.
     * Gating everything with one global default would make the {@code batch.*} configuration a lie —
     * a workspace that set a three-second idle flush would still wait for whatever the default was.
     */
    public List<String> workspacesWithPendingWork(int limit) {
        return jdbc.sql("SELECT DISTINCT q.workspace_name FROM queue q WHERE q.processed = FALSE"
                + " ORDER BY q.workspace_name LIMIT ?").param(limit).query(String.class).list();
    }

    /** Unprocessed items across every workspace. The number an operator watches first. */
    public int pendingCount() {
        return jdbc.sql("SELECT count(*) FROM queue WHERE processed = FALSE").query(Integer.class).single();
    }

    public int pendingCount(String workspace) {
        return jdbc.sql("SELECT count(*) FROM queue WHERE workspace_name = ? AND processed = FALSE").param(workspace)
                .query(Integer.class).single();
    }

    /**
     * Age of the oldest unprocessed item, in seconds; zero when the queue is empty.
     *
     * <p>The single most useful alert in the system. Older than {@code batch.max_age} plus a margin
     * catches a stalled worker, an exhausted provider quota and a leaked claim — three failures that
     * otherwise produce no error anywhere, just memory that quietly stops being written.
     */
    public double oldestPendingSeconds() {
        Double age = jdbc.sql("SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) AS age"
                + " FROM queue WHERE processed = FALSE").query(Double.class).single();
        return age == null ? 0.0 : age;
    }

    public Optional<Instant> oldestPending(String workspace) {
        return jdbc.sql("SELECT min(created_at) AS oldest FROM queue WHERE workspace_name = ? AND processed = FALSE")
                .param(workspace).query((rs, i) -> rs.getTimestamp("oldest")).optional()
                .map(ts -> ts == null ? null : ts.toInstant());
    }
}
