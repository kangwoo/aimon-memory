package dev.dyad.store.repo;

import dev.dyad.core.model.SessionPeer;
import dev.dyad.store.RowMappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionPeerRepository {

    private final JdbcClient jdbc;

    public SessionPeerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Add or re-open a membership without touching the observe settings.
     *
     * <p>This is the form ingestion uses, and it has to be a separate method rather than {@link
     * #join(String, String, String, Boolean, Boolean)} with nulls. That one overwrites both flags with
     * whatever it was handed, so every incoming message was resetting them to null — and null means
     * "defer to the workspace default". A session configured with {@code observe_others: false} started
     * observing again on the peer's next message, silently, in the direction of recording more.
     *
     * <p>A peer who left and came back gets a fresh window, not a resurrected one.
     */
    public void join(String workspace, String session, String peer) {
        jdbc.sql(
                        """
                        INSERT INTO session_peers (workspace_name, session_name, peer_name)
                        VALUES (?, ?, ?)
                        ON CONFLICT (workspace_name, session_name, peer_name) DO UPDATE
                        SET joined_at = CASE WHEN session_peers.left_at IS NULL
                                             THEN session_peers.joined_at ELSE now() END,
                            left_at = NULL
                        """)
                .params(workspace, session, peer)
                .update();
    }

    /**
     * Add or re-open a membership and set its observe settings.
     *
     * <p>Both flags are written as given, nulls included — a caller naming them is stating the whole
     * membership, and "back to the workspace default" has to be expressible. Callers that only mean to
     * record attendance want {@link #join(String, String, String)}.
     */
    public void join(String workspace, String session, String peer, Boolean observeMe, Boolean observeOthers) {
        jdbc.sql(
                        """
                        INSERT INTO session_peers (workspace_name, session_name, peer_name, observe_me, observe_others)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (workspace_name, session_name, peer_name) DO UPDATE
                        SET observe_me = EXCLUDED.observe_me,
                            observe_others = EXCLUDED.observe_others,
                            joined_at = CASE WHEN session_peers.left_at IS NULL
                                             THEN session_peers.joined_at ELSE now() END,
                            left_at = NULL
                        """)
                .params(workspace, session, peer, observeMe, observeOthers)
                .update();
    }

    /** Soft leave: the window closes but the row stays, because past messages still need it. */
    public void leave(String workspace, String session, String peer) {
        jdbc.sql(
                        "UPDATE session_peers SET left_at = now()"
                                + " WHERE workspace_name = ? AND session_name = ? AND peer_name = ? AND left_at IS NULL")
                .params(workspace, session, peer)
                .update();
    }

    public List<SessionPeer> members(String workspace, String session) {
        return jdbc.sql(
                        "SELECT * FROM session_peers WHERE workspace_name = ? AND session_name = ? ORDER BY peer_name")
                .params(workspace, session)
                .query(RowMappers.SESSION_PEER)
                .list();
    }

    /** Who was in the session at the moment something was said. The question fan-out actually asks. */
    public List<SessionPeer> membersAt(String workspace, String session, Instant when) {
        return jdbc.sql(
                        """
                        SELECT * FROM session_peers
                        WHERE workspace_name = ? AND session_name = ?
                          AND joined_at <= ?
                          AND (left_at IS NULL OR left_at > ?)
                        ORDER BY peer_name
                        """)
                .params(workspace, session, java.sql.Timestamp.from(when), java.sql.Timestamp.from(when))
                .query(RowMappers.SESSION_PEER)
                .list();
    }

    public Optional<SessionPeer> find(String workspace, String session, String peer) {
        return jdbc.sql(
                        "SELECT * FROM session_peers WHERE workspace_name = ? AND session_name = ? AND peer_name = ?")
                .params(workspace, session, peer)
                .query(RowMappers.SESSION_PEER)
                .optional();
    }

    /**
     * Replace the whole roster: everyone not named leaves, everyone named joins.
     *
     * <p>Takes the observe settings rather than just the names. Joining with nulls and expecting the
     * caller to set the flags afterwards means every membership passes through a state where it
     * observes by workspace default — briefly, but a message arriving in that window is filed under
     * pairs the caller did not ask for, and nothing afterwards undoes that.
     *
     * <p>Peers must already exist; the composite foreign key enforces it, and that is the point —
     * membership in a session is not a place to conjure a peer into being by typo.
     */
    public void replace(String workspace, String session, List<Membership> roster) {
        jdbc.sql(
                        "UPDATE session_peers SET left_at = now()"
                                + " WHERE workspace_name = ? AND session_name = ? AND left_at IS NULL"
                                + " AND peer_name <> ALL (?)")
                .params(workspace, session, roster.stream().map(Membership::peer).toArray(String[]::new))
                .update();
        for (Membership member : roster) {
            join(workspace, session, member.peer(), member.observeMe(), member.observeOthers());
        }
    }

    /** @param observeMe null defers to the workspace default, as everywhere else */
    public record Membership(String peer, Boolean observeMe, Boolean observeOthers) {

        public static Membership of(String peer) {
            return new Membership(peer, null, null);
        }
    }
}
