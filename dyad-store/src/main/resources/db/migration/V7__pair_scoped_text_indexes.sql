-- Every text query in this system is pair-scoped, and the pair predicate is extremely selective.
-- A GIN index over the whole table's tsvector can therefore never be chosen: reaching one pair
-- through it would mean scanning every workspace's terms and discarding almost all of them, which
-- costs more than reading the pair's own rows. Measured on 60k conclusions across 200 pairs, the
-- planner used the pair btree and applied the text match as a filter every time — the FTS index was
-- built, maintained on every write, and never read.
--
-- btree_gin lets the scalar scope columns live inside the GIN index, so one index serves the pair
-- filter and the text match together. It is not a trade: the composite index measured slightly
-- smaller than the one it replaces (3.5 MB against 4.0 MB at 60k rows), because the scope columns
-- have very low cardinality per entry.
--
-- The vector index is deliberately left alone. HNSW cannot include scalar columns, and it does not
-- need to: the planner switches to it on its own once a single pair is large enough that scanning
-- the pair costs more than walking the graph, which is exactly when it is worth having. It is not
-- cheap — roughly 7 KB per row, 433 MB at 60k conclusions — and that number belongs in capacity
-- planning rather than in a surprise.

CREATE EXTENSION IF NOT EXISTS btree_gin;

DROP INDEX ix_concl_fts;
CREATE INDEX ix_concl_fts ON conclusions
    USING gin (workspace_name, observer, observed, to_tsvector('simple', content_analyzed));

DROP INDEX ix_message_fts;
CREATE INDEX ix_message_fts ON messages
    USING gin (workspace_name, session_name, to_tsvector('simple', content));
