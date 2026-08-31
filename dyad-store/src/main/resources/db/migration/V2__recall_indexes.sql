-- P2. The two Tier 1 retrieval paths get their indexes only once something queries them.

CREATE INDEX ix_concl_hnsw ON conclusions
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- 'simple' rather than a language dictionary: content_analyzed has already been through the
-- workspace's analyzer, so the index must not stem it a second time with the wrong language's rules.
-- This is what lets one index serve Korean, English and bigram-fallback workspaces at once.
CREATE INDEX ix_concl_fts ON conclusions
    USING gin (to_tsvector('simple', content_analyzed));
