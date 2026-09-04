-- P3. Entity matching is semantic, so the node table needs its own vector index.
CREATE INDEX ix_entity_hnsw ON entities
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
