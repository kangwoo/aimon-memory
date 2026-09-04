-- The vector columns follow dyad.embed.dimensions — as a migration of their own, not an edit to V1.
--
-- V1 creates them at 1536 and is left exactly as it shipped. Flyway checksums a migration from its
-- raw bytes, before placeholders are substituted, and validateOnMigrate is on by default: rewriting
-- an already-applied V1 in place does not re-run it, it stops every existing deployment from
-- starting at all, with a checksum mismatch on the next boot of dyad-api or dyad-worker. Fresh
-- databases and the Testcontainers suite build the schema from nothing every time, so neither the
-- local run nor CI would ever have seen it.
--
-- Changing the width of a populated column is not a type change, it is a re-embedding: pgvector
-- cannot reinterpret a 1536-wide value as 3072 wide, and nothing in the row can compute the new one
-- except the content it came from. So this alters the column only while it holds no vectors, and
-- otherwise refuses with both numbers named — the message EmbeddingDimensionCheck gives at startup,
-- arriving instead at the point where the change was actually asked for. Clearing the column is the
-- supported path: the reconciler's embedding backfill exists for exactly that state.
--
-- Note that pgvector's HNSW indexes (V2, V3) only cover vectors up to 2000 dimensions, so a width
-- above that fails here on the index rebuild rather than silently losing semantic recall.
DO $$
DECLARE
    target        integer := ${embedding_dimensions};
    tbl           text;
    current_width integer;
    populated     boolean;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['conclusions', 'entities']
    LOOP
        SELECT a.atttypmod INTO current_width
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = tbl AND a.attname = 'embedding'
          AND n.nspname = current_schema() AND a.attnum > 0 AND NOT a.attisdropped;

        IF current_width IS NULL OR current_width = target THEN
            CONTINUE;
        END IF;

        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE embedding IS NOT NULL)', tbl)
            INTO populated;

        IF populated THEN
            RAISE EXCEPTION
                '%.embedding is vector(%) and dyad.embed.dimensions is %. Widening it discards every '
                'stored vector, so this migration will not do it silently: clear the column '
                '(UPDATE % SET embedding = NULL) and let the reconciler re-embed, or set '
                'dyad.embed.dimensions back to %.',
                tbl, current_width, target, tbl, current_width;
        END IF;

        EXECUTE format('ALTER TABLE %I ALTER COLUMN embedding TYPE vector(%s)', tbl, target);
    END LOOP;
END $$;
