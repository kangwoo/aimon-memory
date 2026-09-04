-- P6. Only the dreamer writes source_ids, and only reasoning-chain traversal reads them.
CREATE INDEX ix_concl_tree ON conclusions USING gin (source_ids jsonb_path_ops);
