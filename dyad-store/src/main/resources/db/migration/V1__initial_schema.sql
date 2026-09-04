-- Dyad initial schema.
--
-- Every column the roadmap needs through P6 is created here, including the ones nothing writes yet:
-- level, source_ids, confidence, expires_at, last_reinforced_at, conclusion_events. Adding the
-- dreamer later is then code, not a migration against a live table with millions of rows.
--
-- Indexes are the deliberate exception and arrive per phase (V2, V3, V4). An unused HNSW index is
-- not free — it slows down every insert to serve a query nobody makes yet.

CREATE EXTENSION IF NOT EXISTS vector;

-- ── Hierarchy ───────────────────────────────────────────────────────────────

CREATE TABLE workspaces (
    name          TEXT PRIMARY KEY,
    metadata      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    configuration JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE peers (
    workspace_name TEXT        NOT NULL REFERENCES workspaces (name) ON DELETE CASCADE,
    name           TEXT        NOT NULL,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    configuration  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_name, name)
);

CREATE TABLE sessions (
    workspace_name    TEXT        NOT NULL REFERENCES workspaces (name) ON DELETE CASCADE,
    name              TEXT        NOT NULL,
    is_active         BOOLEAN     NOT NULL DEFAULT TRUE,
    metadata          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    configuration     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Summaries live here rather than in a table of their own: they are always read with the
    -- session, never queried across sessions, and there are at most two of them.
    internal_metadata JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Allocator for seq_in_session. Bumping it takes the session row lock, which is what makes the
    -- sequence gap-free and monotonic under concurrent writers.
    message_seq       BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_name, name)
);

-- Membership as a time window. Fan-out has to answer "who was in the room when this was said",
-- which is not the same question as "who is in the room now".
CREATE TABLE session_peers (
    workspace_name  TEXT        NOT NULL,
    session_name    TEXT        NOT NULL,
    peer_name       TEXT        NOT NULL,
    observe_me      BOOLEAN,
    observe_others  BOOLEAN,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at         TIMESTAMPTZ,
    PRIMARY KEY (workspace_name, session_name, peer_name),
    FOREIGN KEY (workspace_name, session_name) REFERENCES sessions (workspace_name, name) ON DELETE CASCADE,
    FOREIGN KEY (workspace_name, peer_name)    REFERENCES peers (workspace_name, name)    ON DELETE CASCADE
);

CREATE TABLE messages (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_name TEXT        NOT NULL,
    session_name   TEXT        NOT NULL,
    peer_name      TEXT        NOT NULL,
    content        TEXT        NOT NULL,
    seq_in_session BIGINT      NOT NULL,
    token_count    INTEGER     NOT NULL DEFAULT 0,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_name, session_name) REFERENCES sessions (workspace_name, name) ON DELETE CASCADE,
    FOREIGN KEY (workspace_name, peer_name)    REFERENCES peers (workspace_name, name)    ON DELETE CASCADE,
    CONSTRAINT ux_message_seq UNIQUE (workspace_name, session_name, seq_in_session)
);

CREATE INDEX ix_message_session ON messages (workspace_name, session_name, seq_in_session);
CREATE INDEX ix_message_peer    ON messages (workspace_name, peer_name, created_at DESC);

-- ── The pair ────────────────────────────────────────────────────────────────

-- One row per (observer, observed). It exists so conclusions can carry a composite foreign key to
-- the pair rather than three loose text columns nothing enforces.
CREATE TABLE collections (
    workspace_name         TEXT        NOT NULL REFERENCES workspaces (name) ON DELETE CASCADE,
    observer               TEXT        NOT NULL,
    observed               TEXT        NOT NULL,
    -- Dreamer scheduling guard. Kept on the pair because that is the granularity a dream runs at.
    last_dream_at          TIMESTAMPTZ,
    explicit_at_last_dream INTEGER     NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (observer, observed, workspace_name),
    FOREIGN KEY (workspace_name, observer) REFERENCES peers (workspace_name, name) ON DELETE CASCADE,
    FOREIGN KEY (workspace_name, observed) REFERENCES peers (workspace_name, name) ON DELETE CASCADE
);

-- ── Conclusions ─────────────────────────────────────────────────────────────

CREATE TABLE conclusions (
    id                 TEXT PRIMARY KEY,
    workspace_name     TEXT        NOT NULL,
    observer           TEXT        NOT NULL,
    observed           TEXT        NOT NULL,
    session_name       TEXT,

    content            TEXT        NOT NULL,
    content_norm       TEXT        NOT NULL,   -- lower(btrim(content))            [dedup stage 2]
    content_analyzed   TEXT        NOT NULL,   -- analyzer output, space-joined    [BM25]
    content_hash       CHAR(64)    NOT NULL,   -- sha256(content_norm)             [dedup stage 1]

    level              TEXT        NOT NULL DEFAULT 'explicit',
    confidence         REAL,
    source_ids         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    message_ids        BIGINT[]    NOT NULL DEFAULT '{}',

    times_derived      INTEGER     NOT NULL DEFAULT 1,
    last_reinforced_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    embedding          vector(1536),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    sync_state         TEXT        NOT NULL DEFAULT 'pending',

    FOREIGN KEY (observer, observed, workspace_name)
        REFERENCES collections (observer, observed, workspace_name) ON DELETE CASCADE,
    CONSTRAINT ck_level CHECK (level IN ('explicit', 'deductive', 'inductive', 'contradiction')),
    CONSTRAINT ck_sync_state CHECK (sync_state IN ('pending', 'synced', 'failed')),
    -- An explicit conclusion without a session cannot be traced back to what was said.
    CONSTRAINT ck_explicit_needs_session CHECK (level <> 'explicit' OR session_name IS NOT NULL)
);

CREATE INDEX ix_concl_pair ON conclusions (workspace_name, observer, observed)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_concl_hash ON conclusions (workspace_name, observer, observed, content_hash)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_concl_norm ON conclusions (workspace_name, observer, observed, content_norm)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_concl_expiry ON conclusions (expires_at)
    WHERE deleted_at IS NULL AND expires_at IS NOT NULL;
CREATE INDEX ix_concl_sync ON conclusions (sync_state)
    WHERE deleted_at IS NULL AND sync_state <> 'synced';

-- ── Entities ────────────────────────────────────────────────────────────────

-- Node scope is the workspace, so "서울" is normalised and embedded once no matter how many pairs
-- mention it. Pair isolation is not lost: it moves onto the edge, where the filter belongs anyway.
CREATE TABLE entities (
    id             TEXT PRIMARY KEY,
    workspace_name TEXT        NOT NULL REFERENCES workspaces (name) ON DELETE CASCADE,
    name_norm      TEXT        NOT NULL,
    name_display   TEXT        NOT NULL,
    kind           TEXT,
    embedding      vector(1536),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_name, name_norm)
);

CREATE TABLE entity_links (
    workspace_name TEXT        NOT NULL,
    entity_id      TEXT        NOT NULL REFERENCES entities (id) ON DELETE CASCADE,
    conclusion_id  TEXT        NOT NULL REFERENCES conclusions (id) ON DELETE CASCADE,
    observer       TEXT        NOT NULL,
    observed       TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_name, entity_id, conclusion_id)
);

CREATE INDEX ix_elink_entity     ON entity_links (workspace_name, entity_id, observer, observed);
CREATE INDEX ix_elink_conclusion ON entity_links (conclusion_id);

-- ── Audit ───────────────────────────────────────────────────────────────────

CREATE TABLE conclusion_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_name TEXT        NOT NULL,
    conclusion_id  TEXT        NOT NULL,
    event          TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    before_content TEXT,
    after_content  TEXT,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_event CHECK (event IN ('add', 'reinforce', 'replace', 'delete', 'expire', 'restore')),
    CONSTRAINT ck_actor CHECK (actor IN ('deriver', 'dreamer', 'dedup', 'api', 'reconciler'))
);

-- No foreign key to conclusions on purpose: the audit trail has to outlive a hard delete of the
-- row it describes, otherwise the one event that matters most disappears with its subject.
CREATE INDEX ix_cevent_concl ON conclusion_events (workspace_name, conclusion_id, created_at DESC);
CREATE INDEX ix_cevent_recent ON conclusion_events (workspace_name, created_at DESC);

-- ── Queue ───────────────────────────────────────────────────────────────────

CREATE TABLE queue (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_name TEXT        NOT NULL,
    session_name   TEXT,
    work_unit_key  TEXT        NOT NULL,
    task_type      TEXT        NOT NULL,
    payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    token_count    INTEGER     NOT NULL DEFAULT 0,
    processed      BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

CREATE INDEX ix_queue_pending ON queue (work_unit_key, id) WHERE processed = FALSE;
CREATE INDEX ix_queue_age     ON queue (created_at)        WHERE processed = FALSE;

-- Claiming is an insert, not a lock: a unique violation is how a worker learns someone else already
-- owns this key. No advisory locks, no SELECT FOR UPDATE holding a transaction open across an LLM call.
CREATE TABLE work_unit_claims (
    work_unit_key TEXT PRIMARY KEY,
    worker_id     TEXT        NOT NULL,
    claimed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_claim_expiry ON work_unit_claims (expires_at);

-- ── Dreamer ─────────────────────────────────────────────────────────────────

CREATE TABLE dreams (
    id                   TEXT PRIMARY KEY,
    workspace_name       TEXT        NOT NULL,
    observer             TEXT        NOT NULL,
    observed             TEXT        NOT NULL,
    dream_type           TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'pending',
    conclusions_at_start INTEGER     NOT NULL DEFAULT 0,
    produced             INTEGER     NOT NULL DEFAULT 0,
    error                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    CONSTRAINT ck_dream_type   CHECK (dream_type IN ('consolidate', 'card_refresh')),
    CONSTRAINT ck_dream_status CHECK (status IN ('pending', 'running', 'completed', 'failed'))
);

-- One in-flight dream per pair, enforced by the database rather than by a check-then-insert race.
CREATE UNIQUE INDEX ux_dream_inflight ON dreams (workspace_name, observer, observed)
    WHERE status IN ('pending', 'running');

CREATE TABLE peer_cards (
    workspace_name TEXT        NOT NULL,
    observer       TEXT        NOT NULL,
    observed       TEXT        NOT NULL,
    lines          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_name, observer, observed)
);
