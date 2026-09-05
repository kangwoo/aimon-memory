[한국어](0008-arc42-architecture-doc.md) · **English**

# ADR 0008 — Architecture description collects into one arc42 document, and the specification is left alone

**Status:** accepted · 2026-09-05

## Context

As the documentation grew, the architecture description scattered.

The README had reached 362 lines and was doing six jobs — product pitch, how to run it, the module
graph, design decisions, the test and evaluation gates, and the list of what is not done. The last
four of those are architecture description, not work for a front page.

The same facts were also in three places at once.

| Fact | Where it was |
|---|---|
| The fusion formula | README · `concepts.md` · `guide.md` (+ the frozen specification) |
| The `N + N(N−1)` fan-out cost | README · `concepts.md` · `guide.md` (+ ADR 0006) |
| The three-tier table | README · `concepts.md` · `guide.md` |

The cause was that there was no answer to where a new fact should go. That is a question of
ownership, not of format.

At the same time, creating a new architecture document touches what
[ADR 0005](0005-agpl-boundary.en.md) stands on. That decision's claim is **"the specification is
`aimon-memory-design.md`, and there is only one"**, and the clean-room argument needs that to have a
single answer. An arc42 document is structurally specification-shaped — §1 goals, §3 scope, §5
building blocks, §10 quality requirements. A complete architecture document sitting beside a frozen
specification raises the question of which one is normative.

## Decision

**`docs/architecture.md` is the canonical architecture description, structured as arc42. And it is
descriptive, not normative.**

Four things are settled together.

**1. Descriptive, not normative.** `architecture.md` records what the implementation is, not what it
ought to be. The top of the document says so and points at the frozen specification. The
specification remains `spec/aimon-memory-design.md` alone, and ADR 0005 still stands. Where the two
disagree, that place is either already recorded in an ADR or is where a new one is needed.

**2. The README hands over its architecture description.** The module graph, the design notes, the
test and evaluation gates and the list of what is not done move to `architecture.md`. What remains
in the README is what it is, how to start it, and where to go next. The README is not removed — a
front page has a front page's job.

**3. §7 and §9 reference rather than restate.** Deployment is owned by `runbook.md` and decisions by
`adr/`. arc42's own guidance is to reference ADRs rather than inline them. Both sections record only
what is architecturally significant and point at the canonical source.

**4. `concepts.md`, `guide.md` and `runbook.md` stay as they are.** arc42 §8 (crosscutting concepts)
and §12 (glossary) summarise `concepts.md` and link to it. `guide.md` was never in arc42's scope —
arc42 says of itself that it is not a user manual.

### The ownership table

This table is the substance of the decision.

| Fact | Canonical | Everyone else |
|---|---|---|
| Goals, constraints, context, building blocks, quality, risks | `architecture.md` | link |
| Domain concepts and mechanisms | `concepts.md` | summarise + link |
| How to call it | `guide.md` | link |
| Deployment and operations | `runbook.md` | summarise + link |
| Decisions and their reasoning | `adr/` | table + link |
| Routes, schemas, scopes | `openapi.json` (build-enforced) | link |
| The specification | `spec/` (frozen) | link |

## Consequences

**The clean-room claim survives.** There is still exactly one normative document. `architecture.md`
saying of itself that it is not normative is what pays for that.

**New facts have a place to go.** That, rather than completeness, is why arc42 is worth using: with
twelve named slots there is an answer to "where does this go".

**The README went from 362 lines to 244.** The front page does a front page's job again.

**Three costs.**

There is one more file. Adding a file in order to reduce duplication looks contradictory, but what
fell is not the number of files — it is the number of times the same fact is described.

The arc42 section structure has to be maintained. Leaving a section empty is fine — arc42 says so
itself — but rearranging it at will removes the reason for adopting the format.

And nothing enforces `architecture.md`. Unlike `openapi.json`, no build step holds this document to
the code. The module graph is a partial exception, since `ModuleDependencyTest` effectively guards
it, but that test checks the code rather than the document. This document can therefore rot, and for
now that is accepted.
