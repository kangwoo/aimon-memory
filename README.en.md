[한국어](README.md) · **English**

# aimon-memory

[![ci](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](gradle/libs.versions.toml)
[![docs](https://img.shields.io/badge/docs-kangwoo.github.io-blue.svg)](https://kangwoo.github.io/aimon-memory/)

A memory system for conversational agents. Every fact it stores belongs to a directed
**(observer, observed) pair** — `alice`'s memory of herself and `bot`'s memory of `alice` are
separate stores that never leak into one another.

It runs as two processes behind an HTTP API, and
[`aimon-memory-client`](modules/aimon-memory-client) implements
[aimon-core](https://github.com/kangwoo/aimon-core)'s `PeerMemory` against it — so an aimon-core
application swaps its memory backend for this one by changing which `PeerMemory` it assembles.

Built from `aimon-memory-design.md` and `aimon-memory-build-plan.md`.

The architecture description is [`docs/architecture.en.md`](docs/architecture.en.md) — context
diagram, modules, quality gates and risks, as arc42's twelve sections.

---

## What it does

Messages go in over HTTP and return immediately. A worker batches them, extracts durable facts,
deduplicates against what is already known, and files the result under the pair that was observing.

Extraction runs once per observing pair, not once per batch — the prompt is written from the
observer's side, which is why the cost of opening a large room scales with the number of observers
([ADR 0006](docs/adr/0006-fanout-cost.en.md)).

Reading has three tiers:

| Tier | Call | Model calls | Typical latency | Deterministic |
|---|---|--:|--:|:-:|
| 0 | `context()` | 0 | ~50 ms | yes |
| 1 | **`recall()`** | 0 | ~100 ms | yes |
| 2 | `chat()` | 1–16 | seconds | no |

**Tier 1 is the reason this exists.** Most questions asked of a memory system are lookups, and they
should be fast, cheap and the same answer every time. It ranks with six signals under fixed weights,
and every response carries the breakdown that produced it — the golden fixtures assert all of it to
six decimal places ([`concepts.en.md` §9](docs/concepts.en.md#9-six-signals-and-the-fusion-formula)).

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
## Documents

All of it is published at **<https://kangwoo.github.io/aimon-memory/>** — with search and a
language switcher, built from whatever is on `main`. The list below is for reading them on GitHub
directly.

Korean is the canonical text. Every document has an English counterpart at the same path with an
`.en.md` suffix — this file is `README.md`'s. The two specifications are the exception: they are
Korean only, and [`docs/spec/README.md`](docs/spec/README.md) says why and maps their sections for a
reader who does not read Korean.

Each document **owns something.** Where the same fact appears twice, one of them is a summary and
carries a link to the canonical source
([ADR 0008](docs/adr/0008-arc42-architecture-doc.en.md)).

| Document | What it owns |
|---|---|
| [`docs/architecture.en.md`](docs/architecture.en.md) | **The architecture description.** Goals, constraints, context, building blocks, quality gates, risks. arc42's twelve sections |
| [`docs/concepts.en.md`](docs/concepts.en.md) | **The concepts.** Pairs, the three tiers, the six signals, dedup and forgetting, and why each is shaped that way |
| [`docs/guide.en.md`](docs/guide.en.md) | **The user guide.** From minting a token to tuning recall, followed through in `curl` |
| [`docs/runbook.en.md`](docs/runbook.en.md) | Deploying, tuning, and what to check when something is wrong |
| [`docs/adr/`](docs/adr/README.en.md) | Decision records: where this deviates from the specification, and why |
| [`docs/openapi.json`](docs/openapi.json) | 33 routes, schemas, scopes. A test holds it to the code |
| [`docs/spec/`](docs/spec/README.en.md) | The frozen specification and plan (2026-08-31, Korean) |
| [`docs/dashboards/`](docs/dashboards) | A Grafana dashboard, `aimon-memory-overview.json` |
| [`test-fixtures/README.en.md`](test-fixtures/README.en.md) | The two fixture corpora and what each proves |

If you arrived from aimon-core, start at
[ADR 0007](docs/adr/0007-aimon-core-boundary.en.md) — it draws the boundary between the two
repositories.

## Licence and contributing

Apache-2.0 — [LICENSE](LICENSE). It matches aimon-core, which consumes this, and it is the licence
that [ADR 0005](docs/adr/0005-agpl-boundary.en.md)'s clean-room boundary exists to keep available.

- [CONTRIBUTING.en.md](CONTRIBUTING.en.md) — how to propose a change, and which gates it has to pass
- [SECURITY.en.md](SECURITY.en.md) — how to report a vulnerability
- [CODE_OF_CONDUCT.en.md](CODE_OF_CONDUCT.en.md) — what is expected of people taking part
- [CHANGELOG.en.md](CHANGELOG.en.md) — what changed, per release
