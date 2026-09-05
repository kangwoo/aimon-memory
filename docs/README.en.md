[한국어](README.md) · **English**

# aimon-memory documentation

A memory system for conversational agents. Every fact it stores belongs to a directed
**`(observer, observed)` pair** — `alice`'s memory of herself and `bot`'s memory of `alice` are
separate stores that never leak into one another.

The repository itself is on [GitHub](https://github.com/kangwoo/aimon-memory), and its README covers
building and running it.

## Where to start

| If you are looking for | Read |
|---|---|
| **To call it** | [User guide](guide.en.md) — from minting a token to tuning recall, in `curl` |
| **Why it is shaped this way** | [Concepts](concepts.en.md) — pairs, the three tiers, the six signals, dedup, forgetting |
| **The architecture** | [Architecture](architecture.en.md) — goals, constraints, context, building blocks, quality, risks (arc42) |
| **To operate it** | [Runbook](runbook.en.md) — deployment, migrations, observability, load |
| **The exact shape of a route** | [`openapi.json`](openapi.json) — 33 routes, schemas, scopes |
| **Why a decision went that way** | [ADRs](adr/README.en.md) — where the implementation departed from the specification, and why *(GitHub)* |
| **The original specification** | [Specification and plan](spec/README.en.md) — frozen as of 2026-08-31 *(GitHub)* |
| **To put a dashboard up** | [`dashboards/aimon-memory-overview.json`](dashboards/aimon-memory-overview.json) — a Grafana dashboard; which metrics to watch is in the [runbook](runbook.en.md#observability) |
| **What the fixtures prove** | [Fixtures](../test-fixtures/README.en.md) — the two corpora and what each one proves *(GitHub)* |

If you arrived from aimon-core, start at [ADR 0007](adr/0007-aimon-core-boundary.en.md) — it draws
the boundary between the two repositories.

**The decision records and the specification are not published here.** Both are the record of how
this project got where it is, rather than what someone arriving to find out what it is and how to
use it is looking for. They stay in the repository, and the links above go to GitHub.

## Each document owns something

Where the same fact appears twice, one of them is a summary and carries a link to the canonical
source. The rule and its reasoning are in [ADR 0008](adr/0008-arc42-architecture-doc.en.md).

Korean is canonical. Every document has an `.en.md` counterpart at the same path, and the language
switcher at the top of the page moves between them. The two documents under `spec/` are the
exception and exist in Korean only — the clean-room claim in
[ADR 0005](adr/0005-agpl-boundary.en.md) needs there to be exactly one specification, and
[`spec/README.en.md`](spec/README.en.md) explains the rest.
