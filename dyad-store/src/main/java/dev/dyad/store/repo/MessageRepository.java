package dev.dyad.store.repo;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.Page;
import dev.dyad.store.Jsonb;
import dev.dyad.store.RowMappers;
import dev.dyad.store.Sql;
import dev.dyad.store.filter.CompiledFilter;
import dev.dyad.store.filter.FilterCompiler;
import dev.dyad.store.filter.FilterSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {

    private final JdbcClient jdbc;
    private final FilterCompiler filters = new FilterCompiler(FilterSchema.MESSAGES);

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NewMessage(String peerName, String content, int tokenCount, Map<String, Object> metadata) {}

    /**
     * Insert a batch under a contiguous sequence block.
     *
     * <p>The block is allocated once, so a hundred messages take one session-row lock rather than a
     * hundred, and the ordering within the batch is exactly the order the caller passed.
     */
    public List<Message> insertBatch(String workspace, String session, long startSeq, List<NewMessage> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        long seq = startSeq;
        for (NewMessage message : messages) {
            // RETURNING every column, not just the id. Reading the row back afterwards doubled the
            // round trips for no information the insert could not already hand over — at the
            // documented batch size of 100 that was 200 statements inside one transaction.
            out.add(
                    jdbc.sql(
                                    """
                                    INSERT INTO messages
                                      (workspace_name, session_name, peer_name, content, seq_in_session, token_count, metadata)
                                    VALUES (?, ?, ?, ?, ?, ?, ?)
                                    RETURNING id, workspace_name, session_name, peer_name, content,
                                              seq_in_session, token_count, metadata, created_at
                                    """)
                            .params(
                                    workspace,
                                    session,
                                    message.peerName(),
                                    message.content(),
                                    seq,
                                    message.tokenCount(),
                                    Jsonb.of(message.metadata()))
                            .query(RowMappers.MESSAGE)
                            .single());
            seq++;
        }
        return out;
    }

    public java.util.Optional<Message> byId(String workspace, long id) {
        return jdbc.sql("SELECT " + Sql.MESSAGE_COLUMNS + " FROM messages m WHERE m.workspace_name = ? AND m.id = ?")
                .params(workspace, id)
                .query(RowMappers.MESSAGE)
                .optional();
    }

    public List<Message> byIds(String workspace, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT " + Sql.MESSAGE_COLUMNS
                                + " FROM messages m WHERE m.workspace_name = ? AND m.id = ANY (?) ORDER BY m.id")
                .params(workspace, ids.toArray(Long[]::new))
                .query(RowMappers.MESSAGE)
                .list();
    }

    /** Messages in sequence order, optionally from a starting point — what {@code context()} walks. */
    public List<Message> inSession(String workspace, String session, Long fromSeqInclusive, int limit) {
        return jdbc.sql(
                        "SELECT " + Sql.MESSAGE_COLUMNS
                                + " FROM messages m WHERE m.workspace_name = ? AND m.session_name = ?"
                                + " AND (CAST(? AS bigint) IS NULL OR m.seq_in_session >= ?)"
                                + " ORDER BY m.seq_in_session LIMIT ?")
                .params(workspace, session, fromSeqInclusive, fromSeqInclusive, limit)
                .query(RowMappers.MESSAGE)
                .list();
    }

    /**
     * The newest {@code limit} messages at or after a sequence number, returned oldest first.
     *
     * <p>Distinct from {@link #inSession} in the half that matters. That one pages forward from a
     * starting point and returns the <em>oldest</em> rows after it, which is right for a summariser
     * walking a session in order and wrong for anything showing recent context: past the first page,
     * it hands back the middle of the conversation and silently omits everything since.
     */
    public List<Message> tailFrom(String workspace, String session, long fromSeqInclusive, int limit) {
        List<Message> newestFirst =
                jdbc.sql(
                                "SELECT " + Sql.MESSAGE_COLUMNS
                                        + " FROM messages m WHERE m.workspace_name = ? AND m.session_name = ?"
                                        + " AND m.seq_in_session >= ?"
                                        + " ORDER BY m.seq_in_session DESC LIMIT ?")
                        .params(workspace, session, fromSeqInclusive, limit)
                        .query(RowMappers.MESSAGE)
                        .list();
        List<Message> out = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(out);
        return out;
    }

    public List<Message> tail(String workspace, String session, int limit) {
        List<Message> reversed =
                jdbc.sql(
                                "SELECT " + Sql.MESSAGE_COLUMNS
                                        + " FROM messages m WHERE m.workspace_name = ? AND m.session_name = ?"
                                        + " ORDER BY m.seq_in_session DESC LIMIT ?")
                        .params(workspace, session, limit)
                        .query(RowMappers.MESSAGE)
                        .list();
        List<Message> out = new ArrayList<>(reversed);
        java.util.Collections.reverse(out);
        return out;
    }

    public Page<Message> search(String workspace, String session, Filter filter, int page, int size) {
        CompiledFilter compiled = filters.compile(filter);
        String scope = "m.workspace_name = ?" + (session == null ? "" : " AND m.session_name = ?");
        List<Object> scopeParams = new ArrayList<>();
        scopeParams.add(workspace);
        if (session != null) {
            scopeParams.add(session);
        }

        List<Object> countParams = new ArrayList<>(scopeParams);
        countParams.addAll(compiled.params());
        long total =
                jdbc.sql("SELECT count(*) FROM messages m WHERE " + scope + " AND " + compiled.sql())
                        .params(countParams)
                        .query(Long.class)
                        .single();

        List<Object> pageParams = new ArrayList<>(countParams);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<Message> items =
                jdbc.sql(
                                "SELECT " + Sql.MESSAGE_COLUMNS + " FROM messages m WHERE " + scope
                                        + " AND " + compiled.sql()
                                        + " ORDER BY m.seq_in_session LIMIT ? OFFSET ?")
                        .params(pageParams)
                        .query(RowMappers.MESSAGE)
                        .list();
        return new Page<>(items, page, size, total);
    }

    /**
     * Keyword search over raw message text.
     *
     * <p>{@code websearch_to_tsquery} rather than {@code plainto_tsquery}: it understands quoted
     * phrases and {@code -exclusion}, which is what a model reaches for when its first search returned
     * too much, and it never raises a syntax error on odd input the way {@code to_tsquery} does.
     */
    public List<Message> searchText(String workspace, String session, String query, int limit) {
        String scope = "m.workspace_name = ?" + (session == null ? "" : " AND m.session_name = ?");
        List<Object> params = new ArrayList<>();
        params.add(workspace);
        if (session != null) {
            params.add(session);
        }
        params.add(query);
        params.add(limit);
        return jdbc.sql(
                        "SELECT " + Sql.MESSAGE_COLUMNS + " FROM messages m WHERE " + scope
                                + " AND to_tsvector('simple', m.content) @@ websearch_to_tsquery('simple', ?)"
                                + " ORDER BY m.created_at DESC LIMIT ?")
                .params(params)
                .query(RowMappers.MESSAGE)
                .list();
    }

    /**
     * Case-insensitive substring search.
     *
     * <p>Literal, not regular expression. A caller-supplied pattern reaching Postgres' regex engine is
     * a denial-of-service primitive — the model composes these patterns, and it has no idea what
     * nested quantifiers cost. Substring covers what the tool is actually for, which is locating an
     * exact phrase someone used.
     */
    public List<Message> grep(String workspace, String session, String needle, int limit) {
        String scope = "m.workspace_name = ?" + (session == null ? "" : " AND m.session_name = ?");
        List<Object> params = new ArrayList<>();
        params.add(workspace);
        if (session != null) {
            params.add(session);
        }
        params.add(escapeForLike(needle));
        params.add(limit);
        // ILIKE, not position(). pg_trgm only offers index support for the pattern-matching operators,
        // so position() meant a sequential scan over the whole message table on every call — and the
        // dialectic makes several per question. The trigram index was being maintained and never read.
        return jdbc.sql(
                        "SELECT " + Sql.MESSAGE_COLUMNS + " FROM messages m WHERE " + scope
                                + " AND m.content ILIKE '%' || ? || '%' ESCAPE '\\'"
                                + " ORDER BY m.created_at DESC LIMIT ?")
                .params(params)
                .query(RowMappers.MESSAGE)
                .list();
    }

    /**
     * Neutralise LIKE wildcards in a caller-supplied phrase.
     *
     * <p>The tool is documented to the model as literal text search, and the model composes the
     * argument. Without this, a phrase containing {@code %} silently becomes a wildcard and a search
     * for one sentence returns the entire session.
     */
    static String escapeForLike(String needle) {
        if (needle == null) {
            return "";
        }
        return needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<Message> byDateRange(
            String workspace, String session, java.time.Instant from, java.time.Instant to, int limit) {
        String scope = "m.workspace_name = ?" + (session == null ? "" : " AND m.session_name = ?");
        List<Object> params = new ArrayList<>();
        params.add(workspace);
        if (session != null) {
            params.add(session);
        }
        params.add(java.sql.Timestamp.from(from));
        params.add(java.sql.Timestamp.from(to));
        params.add(limit);
        return jdbc.sql(
                        "SELECT " + Sql.MESSAGE_COLUMNS + " FROM messages m WHERE " + scope
                                + " AND m.created_at >= ? AND m.created_at < ?"
                                + " ORDER BY m.created_at LIMIT ?")
                .params(params)
                .query(RowMappers.MESSAGE)
                .list();
    }

    public int countInSession(String workspace, String session) {
        return jdbc.sql("SELECT count(*) FROM messages WHERE workspace_name = ? AND session_name = ?")
                .params(workspace, session)
                .query(Integer.class)
                .single();
    }
}
