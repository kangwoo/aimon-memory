-- Membership as history, not as one row that forgets.
--
-- session_peers holds one row per (workspace, session, peer), and re-entry moves its joined_at
-- forward. That is right for "is this peer in the room and what are their observe flags", which is
-- all the fan-out asks. It is wrong for the question the dialectic's message tools ask, which is
-- "was this peer in the room when this was said": a peer who left and came back had every earlier
-- window erased, so search_messages, grep_messages and messages_by_date returned nothing from before
-- the rejoin — including the peer's own transcript, reported to the model as "no messages found"
-- rather than as a boundary.
--
-- Widening the predicate to "has ever been a member" would have given back the gap they genuinely
-- did not hear, so the windows are kept instead. session_peers stays the current state and the
-- settings; this is the append-only record of when each one opened and closed.
CREATE TABLE session_peer_windows (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_name TEXT        NOT NULL,
    session_name   TEXT        NOT NULL,
    peer_name      TEXT        NOT NULL,
    joined_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at        TIMESTAMPTZ,
    FOREIGN KEY (workspace_name, session_name) REFERENCES sessions (workspace_name, name) ON DELETE CASCADE,
    FOREIGN KEY (workspace_name, peer_name)    REFERENCES peers (workspace_name, name)    ON DELETE CASCADE
);

-- At most one window open per membership: the upsert that opens one uses this as its conflict
-- target, so an ingest that calls join() on every message adds nothing while the peer is present.
CREATE UNIQUE INDEX ux_speer_window_open ON session_peer_windows (workspace_name, session_name, peer_name)
    WHERE left_at IS NULL;

-- The shape of the correlated EXISTS in MessageRepository.AUDIBLE_TO_OBSERVER.
CREATE INDEX ix_speer_window_lookup
    ON session_peer_windows (workspace_name, session_name, peer_name, joined_at, left_at);

-- Existing memberships become their current window, so nothing that was reachable before this
-- migration stops being reachable after it.
INSERT INTO session_peer_windows (workspace_name, session_name, peer_name, joined_at, left_at)
SELECT workspace_name, session_name, peer_name, joined_at, left_at
FROM session_peers;
