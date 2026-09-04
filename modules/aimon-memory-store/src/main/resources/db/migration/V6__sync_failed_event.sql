-- A failed embedding is a state change worth auditing, and it is not a re-derivation. Folding it
-- into REINFORCE would have inflated the very counter the `reinf` ranking signal reads.
ALTER TABLE conclusion_events DROP CONSTRAINT ck_event;
ALTER TABLE conclusion_events ADD CONSTRAINT ck_event
    CHECK (event IN ('add', 'reinforce', 'replace', 'delete', 'expire', 'restore', 'sync_failed'));
