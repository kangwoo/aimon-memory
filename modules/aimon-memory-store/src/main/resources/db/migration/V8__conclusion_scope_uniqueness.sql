-- Dedup stage 1 is a read followed by a write, and nothing underneath made that pair atomic. Two
-- writers reaching it at the same time both saw no existing row and both inserted. The queue
-- serialises work units for one pair, which hides this for the deriver, but direct injection through
-- the API, `?wait=derive` and the dreamer all reach upsert independently. Measured: twelve concurrent
-- identical writes produced eight rows.
--
-- The index below is the dedup scope written out exactly: the pair, the level, the session for
-- explicit conclusions, and the content hash. coalesce collapses the NULL session that derived
-- conclusions carry, so one index covers both cases -- NULLs are distinct in a unique index, which
-- would otherwise leave the dreamer's output unprotected.
--
-- Existing duplicates have to go first or the index cannot be built. The surviving row is the oldest,
-- which keeps the id that anything else may already reference, and it inherits the reinforcement
-- counts of the copies so the ranking signal is not quietly reset by this migration.

WITH grouped AS (
    SELECT id,
           first_value(id) OVER w AS keep_id,
           sum(times_derived) OVER w AS total_derived,
           max(last_reinforced_at) OVER w AS latest
    FROM conclusions
    WHERE deleted_at IS NULL
    WINDOW w AS (
        PARTITION BY workspace_name, observer, observed, level,
                     coalesce(session_name, ''), content_hash
        ORDER BY created_at, id
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    )
)
UPDATE conclusions c
SET times_derived = g.total_derived,
    last_reinforced_at = g.latest
FROM grouped g
WHERE c.id = g.keep_id AND g.id <> g.keep_id;

WITH grouped AS (
    SELECT id, first_value(id) OVER (
        PARTITION BY workspace_name, observer, observed, level,
                     coalesce(session_name, ''), content_hash
        ORDER BY created_at, id
    ) AS keep_id
    FROM conclusions
    WHERE deleted_at IS NULL
)
UPDATE conclusions c
SET deleted_at = now(), updated_at = now()
FROM grouped g
WHERE c.id = g.id AND g.id <> g.keep_id;

CREATE UNIQUE INDEX ux_concl_scope_hash ON conclusions
    (workspace_name, observer, observed, level, coalesce(session_name, ''), content_hash)
    WHERE deleted_at IS NULL;
