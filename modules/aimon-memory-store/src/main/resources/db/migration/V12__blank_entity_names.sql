-- A blank entity name used to become a node. Every blank normalises to the same empty key, so a
-- workspace did not collect several pieces of junk -- it collected one node named nothing, holding an
-- edge from every unrelated conclusion whose entity list carried a stray empty string. It survives
-- everything that would otherwise remove it: the orphan sweep keeps any node that still has edges, and
-- the reconciler's reindex will happily embed an empty display name and put it back in the index.
--
-- The filter in EntityPipeline.linkAll stops new ones. It does nothing about the ones already stored,
-- and nothing else will either, so they are deleted here. entity_links.entity_id is ON DELETE CASCADE,
-- so the edges go with the node; the conclusions themselves are untouched, since the name was never
-- what made them worth keeping.
DELETE FROM entities WHERE name_norm = '';

-- The constraint is the part that keeps holding. The filter closes all three sources today only
-- because linkAll is the single place a name reaches a node -- the injection endpoint, the deriver and
-- the dreamer all funnel through it -- and that is a property of the current call graph rather than of
-- the schema. A fourth writer, or a filter someone simplifies away, and the node is back, silently and
-- with nothing above the database to notice.
--
-- It cannot fire on model output, which is what makes it safe to add here rather than a way of
-- smuggling in the rejection this design deliberately refused. isUsable is String.isBlank() and
-- normalize() is String.strip(): the same whitespace predicate, so a name the filter keeps always
-- normalises to something non-empty. Reaching this constraint means the code above it is already
-- wrong, and this repository states that kind of invariant in the schema by habit -- ck_level,
-- ck_sync_state, ck_explicit_needs_session are all rules the writer already maintains and the table
-- asserts anyway.
ALTER TABLE entities ADD CONSTRAINT ck_entity_name_norm CHECK (name_norm <> '');
