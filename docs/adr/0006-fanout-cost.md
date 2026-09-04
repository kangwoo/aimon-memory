# ADR 0006 — Extraction runs once per observing pair, not once per batch

**Status:** accepted · 2026-08-31

## Context

`dyad-design.md` §4.4 specifies fan-out as **one LLM call, N collections**: extract the facts from a
batch once, then write the same conclusions into every observing pair. The saving is the point — a
five-person session would otherwise cost five times as much for what the design assumed was the same
answer.

The implementation does not do this. `MessageIngestionService` enqueues one work unit per
`(session, observer, observed)`, `RepresentationConsumer` runs per work unit, and
`Prompts.deriver(observer, observed)` builds a different system prompt for each pair. A message
spoken to four listeners who all observe the speaker costs five extraction calls, not one.

That divergence went unrecorded for a while, and worse, three javadocs asserted the specified
behaviour rather than the actual one — `DeriverService` said "extract once, then write the same
result to every observing pair", which is precisely what does not happen. A comment that describes
the design instead of the code is worse than no comment: it is the thing a reader trusts when
estimating what a group session costs.

## Decision

**Keep the per-pair extraction. Record the cost.**

The premise in the design — that the same answer serves every observer — is wrong, and it is wrong in
the direction the whole product is built around. A pair is a directed memory: what `bob` may conclude
about `alice` from a conversation is not what `alice` concludes about herself, and it is not what the
room as a whole heard. The prompt says so explicitly:

> Record only what %s could reasonably conclude about %s from this conversation.

Sharing one extraction across pairs means one of two things. Either the prompt drops the
observer/observed framing, and every pair stores the same third-person summary — at which point the
pair is a storage detail rather than a perspective, and `PairScope`, the composite foreign key and
the isolation tests are all guarding something that no longer has content. Or the framing is kept for
one privileged pair and copied to the others, which is worse: `bob`'s memory would then contain
`alice`'s conclusions about herself, asserted in her voice, with an audit trail saying `bob` derived
them.

The cost is real and it is the price of the feature. It is **O(observing pairs)** per batch, and the
pairs in a session of N mutually-observing peers are N self-pairs plus N(N−1) cross-pairs. Group
sessions are therefore quadratic in participants, which is the number to know before turning
`observe_others` on for a large room.

## Consequences

- The three javadocs that claimed the opposite are corrected, and the README now states the cost
  where it describes the write path.
- `observe_others` is the lever. It defaults to true, which is right for the two-party case this is
  built for and wrong for a fifty-person channel; a workspace that opens one should turn it off and
  let the speaker's self-pair carry the memory.
- Batching still pays, and pays per pair: the token and idle-flush gates mean a burst of messages to
  the same pair is one call, not one per message. The multiplier is over observers, not over traffic.
- If the quadratic term ever becomes the bill, the change with the best ratio is not a shared
  extraction — it is fanning out **conclusions** from the speaker's self-pair through a cheaper
  per-observer filter, which keeps the perspective and pays for it at a lower rate. That is a design
  change, not a fix, and it needs its own evaluation.
