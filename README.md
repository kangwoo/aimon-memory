# Dyad

A memory system for conversational agents. Every fact it stores belongs to a directed
**(observer, observed) pair** — `alice`'s memory of herself and `bot`'s memory of `alice` are
separate stores that never leak into one another.

Built from `dyad-design.md` and `dyad-build-plan.md`.

---

## What it does

Messages go in over HTTP and return immediately. A worker batches them, extracts durable facts,
deduplicates against what is already known, and files the result under the pair that was observing.

Extraction runs once per observing pair, not once per batch — the prompt is written from the
observer's side, so what `bob` may conclude about `alice` is a different question from what `alice`
concludes about herself. Batching removes the per-message cost, not the per-observer one; a session
of N mutually-observing peers costs N + N(N−1) calls per batch, and `observe_others` is the lever
([ADR 0006](docs/adr/0006-fanout-cost.md)).

Reading has three tiers:

| Tier | Call | Model calls | Typical latency | Deterministic |
|---|---|--:|--:|:-:|
| 0 | `context()` | 0 | ~50 ms | yes |
| 1 | **`recall()`** | 0 | ~100 ms | yes |
| 2 | `chat()` | 1–10 | seconds | no |

**Tier 1 is the reason this exists.** Most questions asked of a memory system are lookups, and they
should be fast, cheap and the same answer every time. It ranks with six signals under fixed weights:

```
score = 0.50·sem + 0.22·kw + 0.13·ent + 0.08·reinf + 0.05·rec + 0.02·lvl
```

Every response carries the breakdown that produced it, and the golden fixtures assert all of it to
six decimal places.

Tier 2 is still there for the questions Tier 1 cannot answer — enumerations, contradictions, anything
narrative — and it has Tier 1 as one of its tools, which is what keeps its iteration count down.

---

## Running it

```sh
docker compose up -d               # postgres 16 + pgvector
./gradlew check                    # 360 tests, Testcontainers starts its own database

export DYAD_JWT_SECRET=$(openssl rand -base64 48)
./gradlew :dyad-api:bootRun        # HTTP, port 8080
./gradlew :dyad-worker:bootRun
```

`DYAD_JWT_SECRET` is required and has no default. A development default in `application.yml` is a
signing key published in the repository: a deployment that forgets the variable would start cleanly,
sign production tokens with it, and hand an admin token to anyone who has read the source. Startup
fails instead.

Everything else starts without credentials. The embedder falls back to a local hashing implementation
and the model provider to one that fails with a clear message when something asks for a completion —
enough to exercise every path, and clearly labelled as not suitable for anything else.

With real providers:

```sh
export OPENAI_API_KEY=...
export DYAD_LLM_PROVIDER=openai
export DYAD_EMBED_PROVIDER=openai
export DYAD_LLM_FALLBACK=anthropic ANTHROPIC_API_KEY=...   # optional
```

### Smoke test

`scripts/smoke.sh` drives a running pair of processes over HTTP: health, workspace, ingestion,
conclusion injection, Korean recall with the full signal breakdown, entity provenance, audit trail.
It proves the things unit tests cannot — that both jars boot, that Flyway applies every migration to
an empty database, and that Nori analysis reaches the query path.

```sh
DYAD_JWT_SECRET=... ./scripts/smoke.sh
```

### A first request

```sh
TOKEN=$(curl -s localhost:8080/v1/tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scope":"workspace","workspace":"demo"}' | jq -r .token)

curl -s localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"messages":[{"peer":"alice","content":"I work at a bank in Gangnam, Seoul."}]}'

curl -s localhost:8080/v1/workspaces/demo/recall \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"where does alice work","observer":"alice","observed":"alice"}'
```

That workspace token can mint further tokens itself, as long as each is no wider than it is — a
session token for one conversation, handed to a browser, without an admin key going anywhere near
the service that issues it. Widening is refused: a different workspace, a broader scope, or a peer
the caller does not already speak for.

---

## Modules

Dependencies point downwards only, enforced by `ModuleDependencyTest`.

```
dyad-core      no dependencies. Domain types, the six SPIs, key encoding.
dyad-testkit   core. Golden fixtures, stubs, the Testcontainers base.

dyad-text      core. Nori / Standard / bigram analyzers, normalisation, BM25, jtokkit.
dyad-embed     core, text. Batching, truncation, retry, order preservation.
dyad-llm       core. Provider backends, structured output, tool loop, record/replay.
dyad-store     core, text. Flyway, repositories, pgvector, the filter compiler.

dyad-recall    core, store, text, embed. Six signals, fusion, explain, provenance.
dyad-memory    + llm, recall. Deriver, summariser, context, dialectic, dreamer.

dyad-worker    memory, store.            [runnable]
dyad-api       recall, memory, store.    [runnable]
```

Two processes, one codebase. They scale differently and fail differently: the API is latency-bound
and can be restarted freely; the worker holds queue claims that a restart has to release.

---

## Design notes

Six decisions that are not obvious from the code.

**Memory belongs to a pair, decided on day one.** `(workspace, observer, observed)` is a composite
foreign key on every conclusion. Retrofitting this is not a migration, it is a rewrite, which is why
it is here before anything needs it.

**The ranking denominator is constant.** Weights sum to 1.00 and stay there whether or not a signal
fired. The system this formula derives from rescaled by how many stores answered, so the same
conclusion scored differently depending on configuration. Here a missing signal contributes zero: the
score drops honestly and the ordering among candidates is untouched.

**The threshold cuts the fused score.** Cutting on semantic similarity alone discards exactly the
rows an exact keyword match was about to rescue.

**Forgetting is measured from the last reinforcement, not from creation.** A fact that keeps coming
up keeps resetting its own clock and never ages; something mentioned once slides down on its own.
`times_derived` was already being maintained by dedup — one source system counts it without ranking
on it, the other ranks without having it.

**Language is a column, not an index setting.** `content_analyzed` is produced by the workspace's
analyzer at write time and indexed with the `simple` dictionary. One index serves Korean, English and
bigram-fallback workspaces, and swapping an analyzer is a re-index rather than a migration.

**Everything that changes a conclusion writes an event.** Not bookkeeping — the dreamer edits memory
with nobody watching, and without the log there is no answer to "where did this come from" about a
belief no human ever stated.

---

## Testing

```
360  tests, all green
```

| Layer | Method | Gate |
|---|---|---|
| Ranking | golden fixtures | six decimals, zero rank inversions |
| Dedup | Testcontainers | one test per branch and per boundary |
| Normalisation | Java vs SQL, side by side | character-for-character parity |
| LLM paths | record/replay | CI is replay-only; a miss is a failure |
| Authorisation | route allowlist | a route with no policy entry fails the build |
| Architecture | ArchUnit | dependencies point one way |
| Index usage | `EXPLAIN` with seqscan disabled | every index reachable by its real query |
| Ranking quality | nDCG / MRR against a baseline | no regression, per query and in aggregate; the baseline records the corpus it was measured on |
| Load | concurrent readers and writers | no errors, gap-free sequence under contention (CI runs a small profile) |
| Configuration | validated at the write boundary | an unknown key or an unusable value is a 422, never a silent fallback |
| Provider wire format | a real server on an ephemeral port | the request body is asserted, not the object that produced it |

Two things worth knowing about the suite.

`RoutePolicyCoverageTest` walks the live handler mappings and fails if any route is missing from
`RoutePolicy`. Adding an endpoint without deciding who may call it breaks the build, rather than
shipping with whatever the framework's default was.

`NormalisationParityTest` compares `Normalizer.normalize` against its SQL twin for every input that
has ever caused trouble. It found a real divergence during development: Postgres `btrim` strips only
spaces while Java's `strip()` strips all whitespace, so tabbed content would have skipped dedup stage
2 silently. Both sides are now pinned to one explicit rule.

Golden fixtures prove the formula is implemented as specified. They say nothing about whether the
weights are any good — that needs a labelled evaluation set, and it is a separate gate.

---

## Evaluation

Three gates, deliberately separate, because they fail for different reasons.

**Golden fixtures** (`test-fixtures/golden/`) pin every signal and the fused score to six decimal
places. They prove the formula is implemented as specified. They cannot tell a correct implementation
of a bad formula from a correct implementation of a good one, because a wrong weight produces a
consistent answer the fixture faithfully records.

**A labelled ranking set** (`test-fixtures/eval/ranking.json`) — 40 Korean conclusions, 50 queries,
103 graded judgements — scored with nDCG@5/@10, MRR and recall@10 against a committed baseline. The
gate is one-sided: improvements pass, regressions fail, per query as well as in aggregate.

```
mean nDCG@5 0.671   nDCG@10 0.700   MRR 0.795   recall@10 0.655
```

Read those as a regression baseline, not as a quality claim. Without provider credentials the
embedder is lexical, so `sem` behaves like a second keyword signal — which is exactly why the weakest
queries are the conceptual ones (*"앨리스의 여행 계획"* scores 0.000, because nothing lexical connects
"여행 계획" to "오사카행 항공권을 예약했다") and the strongest are the ones naming a term outright. The
gap between those two numbers is a fair estimate of what a real embedder has to buy. Twelve of the
fifty queries score below 0.35 and every one of them is conceptual, which is the shape of that gap.

Verified by breaking it: shifting the weights onto recency drops the mean and the gate fails.

**A qualitative dialectic set** (`test-fixtures/eval/dialectic.json`) — 30 queries across
enumeration, supersession, contradiction, abstention and provenance, with a weighted rubric. Graded
by a person, because whether an answer is grounded or merely plausible is a judgement and a scripted
model would be marking its own homework. `./scripts/dialectic-sheet.sh` prints the scoring sheet; a
test keeps the set itself from rotting.

## Not done

Stated plainly rather than left to be discovered.

- **The ranking weights are still untuned against real intent.** The gate above catches regressions;
  it cannot say the weights are right, because the judgements are scored against a synthetic
  embedder. That needs real traffic, and the weights are configuration for exactly this reason.
- **The evaluation set is 50 queries, not the 100–200 the plan asked for.** Deliberately: with a
  lexical stand-in for the embedder, more hand-written judgements sharpen the regression gate and add
  nothing to confidence in the weights. The rest of that gap closes with traffic, not with authoring.
- **No committed LLM fixtures.** The replay mechanism is proven end to end, including a multi-step
  tool loop, but every recorded call in the suite comes from a scripted backend.
  `./scripts/record-fixtures.sh` records against a real provider once credentials exist.
- **Prompts are unscored.** The set and the rubric are written; nobody has run them against a real
  model and filled the sheet in.
- **Load figures are from a laptop.** The harness and the numbers are real (see the runbook), but
  they describe a container on a developer machine, not production hardware. CI runs the profile at a
  small size for its assertions — no errors, a gap-free sequence — and ignores its timings.

## Documents

- `docs/spec/dyad-design.md` — the specification
- `docs/spec/dyad-build-plan.md` — the plan this was built from
- `docs/adr/` — where this deviates from either, and why, with the evidence
- `docs/runbook.md` — deploying, tuning, and what to check when something is wrong
- `docs/dashboards/` — Grafana
