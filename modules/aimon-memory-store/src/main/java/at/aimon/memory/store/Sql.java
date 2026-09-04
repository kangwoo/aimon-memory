package at.aimon.memory.store;

/** Column lists, spelled once. A missing column in a projection is a NPE far from its cause. */
public final class Sql {

    public static final String CONCLUSION_COLUMNS = """
            c.id, c.workspace_name, c.observer, c.observed, c.session_name,
            c.content, c.content_norm, c.content_analyzed, c.content_hash,
            c.level, c.confidence, c.source_ids, c.message_ids,
            c.times_derived, c.last_reinforced_at, c.created_at, c.updated_at,
            c.expires_at, c.deleted_at, c.sync_state
            """;

    public static final String MESSAGE_COLUMNS = "m.id, m.workspace_name, m.session_name, m.peer_name, m.content,"
            + " m.seq_in_session, m.token_count, m.metadata, m.created_at";

    private Sql() {
    }
}
