# ADR 0005 — Clean-room boundary

**Status:** accepted · 2026-08-31

## Context

One of the two systems this design draws on is AGPL-3.0. Architecture and concepts are not
copyrightable; code and prompt strings are, and copying either would make this project AGPL.

## Decision

`aimon-memory-design.md` is the only specification. No source from either original was consulted while
building this, and neither repository is present in this working tree.

Everything expressive is written from scratch:

- **Prompts.** `at.aimon.memory.engine.prompt.Prompts` — extraction, summarisation, dialectic, the three
  dream specialists, the peer card. Written against the behaviours the specification describes, from
  the failure modes each one has to avoid.
- **Schemas.** The structured-output schemas are hand-written, with descriptions aimed at a model
  rather than at a code generator.
- **SQL.** Written from the schema in the specification, with the additions the design calls for
  (`work_unit_claims`, `dreams`, `peer_cards`, the staged index migrations).

The scoring formulae come from the Apache-2.0 system, which permits it, and are cited as such in the
classes that implement them.

## Consequence

Prompt quality has to be established empirically rather than inherited. That is a known cost, and it
is why the fixture corpus and a qualitative evaluation set are part of the plan rather than an
afterthought.
