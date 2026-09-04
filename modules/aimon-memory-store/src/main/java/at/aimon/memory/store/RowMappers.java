package at.aimon.memory.store;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.ConclusionEvent;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.core.model.Peer;
import at.aimon.memory.core.model.Session;
import at.aimon.memory.core.model.SessionPeer;
import at.aimon.memory.core.model.SyncState;
import at.aimon.memory.core.model.Workspace;

/** Result-set to domain mapping, in one place so the column names appear exactly twice: here and in SQL. */
public final class RowMappers {

    public static final RowMapper<Workspace> WORKSPACE = (rs, i) -> new Workspace(rs.getString("name"),
            Jsonb.toMap(rs.getString("metadata")), Jsonb.toMap(rs.getString("configuration")),
            instant(rs, "created_at"));

    public static final RowMapper<Peer> PEER = (rs, i) -> new Peer(rs.getString("name"), rs.getString("workspace_name"),
            Jsonb.toMap(rs.getString("metadata")), Jsonb.toMap(rs.getString("configuration")),
            instant(rs, "created_at"));

    public static final RowMapper<Session> SESSION = (rs, i) -> new Session(rs.getString("name"),
            rs.getString("workspace_name"), rs.getBoolean("is_active"), Jsonb.toMap(rs.getString("metadata")),
            Jsonb.toMap(rs.getString("configuration")), Jsonb.toMap(rs.getString("internal_metadata")),
            instant(rs, "created_at"));

    public static final RowMapper<SessionPeer> SESSION_PEER = (rs, i) -> new SessionPeer(rs.getString("workspace_name"),
            rs.getString("session_name"), rs.getString("peer_name"), nullableBoolean(rs, "observe_me"),
            nullableBoolean(rs, "observe_others"), instant(rs, "joined_at"), instant(rs, "left_at"));

    public static final RowMapper<Message> MESSAGE = (rs, i) -> new Message(rs.getLong("id"),
            rs.getString("workspace_name"), rs.getString("session_name"), rs.getString("peer_name"),
            rs.getString("content"), rs.getLong("seq_in_session"), rs.getInt("token_count"),
            Jsonb.toMap(rs.getString("metadata")), instant(rs, "created_at"));

    public static final RowMapper<Conclusion> CONCLUSION = (rs, i) -> new Conclusion(rs.getString("id"),
            new PairKey(rs.getString("workspace_name"), rs.getString("observer"), rs.getString("observed")),
            rs.getString("session_name"), rs.getString("content"), rs.getString("content_norm"),
            rs.getString("content_analyzed"), rs.getString("content_hash"),
            ConclusionLevel.fromWire(rs.getString("level")), nullableDouble(rs, "confidence"),
            Jsonb.toStringList(rs.getString("source_ids")), longArray(rs, "message_ids"), rs.getInt("times_derived"),
            instant(rs, "last_reinforced_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
            instant(rs, "expires_at"), instant(rs, "deleted_at"), SyncState.fromWire(rs.getString("sync_state")));

    public static final RowMapper<ConclusionEvent> EVENT = (rs, i) -> new ConclusionEvent(rs.getLong("id"),
            rs.getString("workspace_name"), rs.getString("conclusion_id"), EventType.fromWire(rs.getString("event")),
            at.aimon.memory.core.model.Actor.fromWire(rs.getString("actor")), rs.getString("before_content"),
            rs.getString("after_content"), Jsonb.toMap(rs.getString("detail")), instant(rs, "created_at"));

    private RowMappers() {
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    public static List<Long> longArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<Long> out = new ArrayList<>(values.length);
        for (Object value : values) {
            out.add(((Number) value).longValue());
        }
        return out;
    }
}
