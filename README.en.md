[한국어](README.md) · **English**

# aimon-memory

[![ci](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](gradle/libs.versions.toml)

A memory system for conversational agents. Every fact it stores belongs to a directed
**(observer, observed) pair** — `alice`'s memory of herself and `bot`'s memory of `alice` are
separate stores that never leak into one another.

It runs as two processes behind an HTTP API, and
[`aimon-memory-client`](modules/aimon-memory-client) implements
[aimon-core](https://github.com/kangwoo/aimon-core)'s `PeerMemory` against it — so an aimon-core
application swaps its memory backend for this one by changing which `PeerMemory` it assembles.

Built from `aimon-memory-design.md` and `aimon-memory-build-plan.md`.

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

### Requirements

- **JDK 21.** Pinned as `java` in `gradle/libs.versions.toml`. The Gradle wrapper is committed, so
  `./gradlew` needs nothing installed beyond the JDK.
- **Docker.** For `docker compose up` and for the Testcontainers tier, which starts its own database.

A fresh clone builds and passes every gate. `aimonCore` in `gradle/libs.versions.toml` is a released
`0.2.4` on Central, so nothing on the path from `git clone` to `checkAll` reaches an artifact only one
machine has. One tier is the exception, and it skips itself rather than failing the build:
`:aimon-memory-client:contractTest` subclasses `at.aimon.core:aimon-memory-testkit`, which is on no
remote repository until aimon-core 0.3.0 ships. It is pinned separately as `aimonTestkit` and resolved
through `mavenLocal()`, and the skip names the reason. To run that tier, produce the artifact first:

```sh
# from an aimon-core checkout
./gradlew publishToMavenLocal -PVERSION_NAME=0.3.0-SNAPSHOT
```

Why the coordinate is split in two, and what the suite found when it was first run, is
[ADR 0007](docs/adr/0007-aimon-core-boundary.md).

```sh
docker compose up -d                 # postgres 16 + pgvector
./gradlew checkAll                   # format, style, BOM, and the tests that need no daemon
./gradlew integrationTest            # the Testcontainers tier, which starts its own database

export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
./gradlew :aimon-memory-api:bootRun          # HTTP, port 8080
./gradlew :aimon-memory-worker:bootRun
```

`checkAll` is the fast gate and `integrationTest` is where nearly all of this system's behaviour is
actually proven — a partial unique index, a pgvector distance and a Flyway migration chain are not
things a mock stands in for. Both are gates in CI; they are separated so a formatting mistake does
not wait behind a database.

`AIMON_MEMORY_JWT_SECRET` is required and has no default. A development default in `application.yml` is a
signing key published in the repository: a deployment that forgets the variable would start cleanly,
sign production tokens with it, and hand an admin token to anyone who has read the source. Startup
fails instead.

Everything else starts without credentials. The embedder falls back to a local hashing implementation
and the model provider to one that fails with a clear message when something asks for a completion —
enough to exercise every path, and clearly labelled as not suitable for anything else.

With real providers:

```sh
export OPENAI_API_KEY=...
export AIMON_MEMORY_LLM_PROVIDER=openai
export AIMON_MEMORY_EMBED_PROVIDER=openai
export AIMON_MEMORY_LLM_FALLBACK=anthropic ANTHROPIC_API_KEY=...   # optional
```

### Smoke test

`scripts/smoke.sh` drives a running pair of processes over HTTP: health, workspace, ingestion,
conclusion injection, Korean recall with the full signal breakdown, entity provenance, audit trail.
It proves the things unit tests cannot — that both jars boot, that Flyway applies every migration to
an empty database, and that Nori analysis reaches the query path.

```sh
AIMON_MEMORY_JWT_SECRET=... ./scripts/smoke.sh
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

All thirty-three routes are in [`docs/openapi.json`](docs/openapi.json), with their request and
response schemas and the token scope each one needs. To click through them, point any Swagger UI at
that file. To fetch it from a running service, start with `AIMON_MEMORY_OPENAPI=true` and read
`/v3/api-docs` — off by default, because the auth interceptor covers `/v1/**` and nothing else.

---

## Modules

Dependencies point downwards only, enforced by `ModuleDependencyTest`.

```
aimon-memory-core      no dependencies. Domain types, the six SPIs, key encoding.
aimon-memory-testkit   core. Golden fixtures, stubs, the Testcontainers base.

aimon-memory-text      core. Nori / Standard / bigram analyzers, normalisation, BM25, jtokkit.
aimon-memory-embed     core, text. Batching, truncation, retry, order preservation.
aimon-memory-llm       core. Provider backends, structured output, tool loop, record/replay.
aimon-memory-store     core, text. Flyway, repositories, pgvector, the filter compiler.

aimon-memory-recall    core, store, text, embed. Six signals, fusion, explain, provenance.
aimon-memory-engine    + llm, recall. Deriver, summariser, context, dialectic, dreamer.

aimon-memory-worker    engine, store.            [runnable]
aimon-memory-api       recall, engine, store.    [runnable]

aimon-memory-client    aimon-core only.          [the adapter, Java 17]
aimon-memory-bom       nothing. A java-platform pinning the published modules.
```

Two processes, one codebase. They scale differently and fail differently: the API is latency-bound
and can be restarted freely; the worker holds queue claims that a restart has to release.

`aimon-memory-engine` is the tiers themselves — deriver, dialectic, dreamer, fan-out, ingestion. It
is named `engine` rather than `memory` so neither the module nor its package repeats the product's
name.

---

## Using it from aimon-core

`aimon-memory-client` is the seam. aimon-core replaces a memory backend at
`at.aimon.core.memory.PeerMemory` — five tiers, at service altitude, with a comment that anticipates
exactly this case: "the store-backed default and a remote memory service both have a name for" them.

```java
PeerMemory memory = new RemotePeerMemory(RemoteMemoryOptions.builder()
        .baseUri("https://memory.internal:8080")
        .token(tokens::current)          // called per request; tokens expire
        .agentPeer("assistant")          // who ASSISTANT-role messages are stored as
        .build());
```

| aimon-core tier | endpoint |
| --- | --- |
| `SNAPSHOT` | `GET /v1/workspaces/{ws}/conclusions` |
| `SEARCH` | `POST /v1/workspaces/{ws}/recall` |
| `CHAT` | `POST /v1/workspaces/{ws}/chat` |
| `OBSERVE` | `POST /v1/workspaces/{ws}/conclusions` |
| `INGEST` | `POST /v1/workspaces/{ws}/sessions/{session}/messages` |

The pair maps across without translation: aimon-core's subject is the observed peer and its observer
is the observer, which is the same directed pair this system keys every row on. A query with no
observer names the subject's own self-pair.

Three capability signals are false, and each is a real difference rather than an omission — recall
is not narrowed by session, because a conclusion outlives the session that produced it; an injected
observation's confidence is derived from its level and its reinforcement rather than supplied; and
ingestion queues rather than derives, so a receipt never reports `derived`.

It builds against `at.aimon.core:aimon-core:0.2.4`, the first release containing the five tiers, and
needs nothing but that coordinate — no sibling checkout, and no composite build.
`:aimon-memory-client:verifyCoreIsReleased` is what keeps that true: it refuses a publish when
aimon-core resolved to a project rather than a released artifact, when it resolved to a snapshot, or
when the jar it resolved does not actually contain `PeerMemory`.

The contract suite is the one thing that reaches past that coordinate.
`at.aimon.core:aimon-memory-testkit` first ships in aimon-core 0.3.0, so it is pinned separately as
`aimonTestkit` and `:aimon-memory-client:contractTest` is a source set of its own that skips itself
where the artifact is absent — see [Running it](#running-it).

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
406  tests, all green
165  of them need no database (`checkAll`)
241  of them do (`integrationTest`)
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
| API description | generated against committed | fails when `docs/openapi.json` falls behind, or a route has no summary and scope |
| aimon-core adapter | a real server on an ephemeral port | the pair's direction, the five tiers' bodies, and the three honest capability signals |

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

Korean is the canonical text. Every document has an English counterpart at the same path with an
`.en.md` suffix — this file is `README.md`'s. The two specifications are the exception: they are
Korean only, and [`docs/spec/README.md`](docs/spec/README.md) says why and maps their sections for a
reader who does not read Korean.

- [`docs/spec/aimon-memory-design.md`](docs/spec/aimon-memory-design.md) — the specification (Korean)
- [`docs/spec/aimon-memory-build-plan.md`](docs/spec/aimon-memory-build-plan.md) — the plan this was
  built from (Korean)
- [`docs/adr/`](docs/adr/README.en.md) — where this deviates from either, and why, with the evidence.
  [ADR 0007](docs/adr/0007-aimon-core-boundary.en.md) is the one to read first if you arrived from aimon-core:
  it draws the boundary between the two repositories
- [`docs/openapi.json`](docs/openapi.json) — the 33 routes, their request and response schemas, and the
  scope each one needs. Generated from the running application and committed; a test holds the two together
- [`docs/runbook.en.md`](docs/runbook.en.md) — deploying, tuning, and what to check when something is wrong
- [`docs/dashboards/`](docs/dashboards) — a Grafana dashboard, `aimon-memory-overview.json`
- [`test-fixtures/README.en.md`](test-fixtures/README.en.md) — the two fixture corpora and what each proves

## Licence and contributing

Apache-2.0 — [LICENSE](LICENSE). It matches aimon-core, which consumes this, and it is the licence
that [ADR 0005](docs/adr/0005-agpl-boundary.en.md)'s clean-room boundary exists to keep available.

- [CONTRIBUTING.en.md](CONTRIBUTING.en.md) — how to propose a change, and which gates it has to pass
- [SECURITY.en.md](SECURITY.en.md) — how to report a vulnerability
- [CODE_OF_CONDUCT.en.md](CODE_OF_CONDUCT.en.md) — what is expected of people taking part
- [CHANGELOG.en.md](CHANGELOG.en.md) — what changed, per release
