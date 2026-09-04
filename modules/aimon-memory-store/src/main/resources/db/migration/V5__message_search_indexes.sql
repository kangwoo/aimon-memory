-- P5. The dialectic tools read messages directly, which nothing did before this point.

-- 'simple' for the same reason conclusions use it: the language belongs to the analyzer, not the
-- index. Message text is not pre-analysed, so this is a plain word index — good enough for the
-- keyword tool, which exists to find a turn, not to rank a corpus.
CREATE INDEX ix_message_fts ON messages USING gin (to_tsvector('simple', content));

-- Trigram index for the substring tool. Without it, phrase search over a long session is a
-- sequential scan on every tool call, and a dialectic loop makes several.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_message_trgm ON messages USING gin (content gin_trgm_ops);
