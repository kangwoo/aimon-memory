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
machine has.

That now includes the contract tier. `:aimon-memory-client:contractTest` subclasses
`at.aimon.core:aimon-memory-testkit`, which has no release yet — it first ships in aimon-core 0.3.0 —
but is published to Central's snapshot repository, and the build opens that repository for that one
coordinate. So the tier runs straight after a clone, with nothing to publish by hand.

Why the coordinate is split in two, and what the suite found when it was first run, is
[ADR 0007](docs/adr/0007-aimon-core-boundary.en.md).

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

## Consuming it

**Nothing has gone to Maven Central yet.** `VERSION_NAME` is `0.1.0-SNAPSHOT`, and a release is
triggered by a tag like `v0.1.0`. Until then, publish locally and use it from there.

```sh
./gradlew publishToMavenLocal
```

An aimon-core application needs one artifact, the adapter.

```kotlin
repositories {
    mavenLocal()          // once the first release is out, mavenCentral() alone is enough
}

dependencies {
    implementation(platform("at.aimon.memory:aimon-memory-bom:0.1.0-SNAPSHOT"))
    implementation("at.aimon.memory:aimon-memory-client")
}
```

In Maven, import the BOM through `dependencyManagement`.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>at.aimon.memory</groupId>
      <artifactId>aimon-memory-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

`aimon-memory-client` **depends on nothing but aimon-core.** An application that wants remote memory
should not end up with pgvector, Flyway, Lucene and a Spring Boot application on its classpath, and
none of the five tiers needs them. The bytecode is Java 17 — aimon-core's floor, and the only thing
this module compiles against.

The assembly code and the endpoint behind each tier are in
[`docs/guide.en.md` §15](docs/guide.en.md#15-using-it-from-aimon-core).

### Why there is a BOM

So a version is not repeated on every dependency line. It is a `java-platform`, and two things about
it are deliberate.

**It constrains its own modules and leaves third-party versions alone.** Pinning Spring Boot or
Jackson here would look helpful and would do harm — Gradle treats a `platform()`'s versions as
recommendations, but Maven's `dependencyManagement` and `enforcedPlatform` treat them as overrides. A
Maven application importing this BOM would have its Boot-managed versions quietly moved to whatever
this repository happened to build against.

**The list is generated by the build, not written by hand.** A hand-maintained BOM is a BOM that is
one release behind. And the check reads the coordinates each module declares in `gradle.properties`
rather than the generated list — comparing a generated list against itself only proves the list
equals itself. That catches the two half-published cases: a module that applies the plugin without
declaring coordinates, and one that declares coordinates without applying the plugin.

### What is published and what is not

| | |
|---|---|
| **Published** | `aimon-memory-bom` · `-client` · `-core` · `-embed` · `-engine` · `-llm` · `-recall` · `-store` · `-text` |
| Not published | `aimon-memory-api` · `-worker` — running processes, not libraries. See [Running it](#running-it) |
| Not published | `aimon-memory-testkit` — this repository's test harness |

If you are going to run the service and talk to it over HTTP only, you need no dependency at all. The
routes are in [`docs/openapi.json`](docs/openapi.json) and how to call them is in
[`docs/guide.en.md`](docs/guide.en.md).

## Documents

All of it is published at **<https://kangwoo.github.io/aimon-memory/>** — with search and a
language switcher, built from whatever is on `main`. The list below is for reading them on GitHub
directly.

Korean is the canonical text. Every document has an English counterpart at the same path with an
`.en.md` suffix — this file is `README.md`'s. The two specifications are the exception: they are
Korean only, and [`docs/spec/README.en.md`](docs/spec/README.en.md) says why and maps their sections
for a reader who does not read Korean.

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
