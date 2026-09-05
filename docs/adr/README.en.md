[한국어](README.md) · **English**

# Architecture Decision Records

Where this implementation departs from the specification and the plan, and why, with the evidence.
Decisions that did not depart are not here — this does not restate what the design document already
says.

Korean is the canonical text; each record has an English counterpart at the same name with an
`.en.md` suffix. The file paths do not move: the README, commit messages and code comments cite them.

| No. | Title | Status | In one line |
|---|---|---|---|
| [0001](0001-stack.en.md) | Stack | accepted · 2026-08-31 | Java 21, Spring MVC on virtual threads and one Postgres, rather than the plan's Java 25, WebFlux and three stores |
| [0002](0002-persistence.en.md) | Spring JDBC with hand-written SQL, not jOOQ codegen | accepted · 2026-08-31 | Codegen wants a live database on every build, and the dynamic query is one `FilterCompiler` |
| [0003](0003-llm-transport.en.md) | Direct HTTP to providers, not their SDKs | accepted · 2026-08-31 | The abstraction has to exist for the tool loop anyway, and an SDK does not offer the record/replay seam |
| [0004](0004-half-life.en.md) | The recency signal uses a real half-life | accepted · 2026-08-31 | The spec's `exp(−Δ/H)` halves at 125 days, not 180. Corrected to `0.5^(Δ/H)` |
| [0005](0005-agpl-boundary.en.md) | Clean-room boundary | accepted · 2026-08-31 | `aimon-memory-design.md` is the only specification; prompts, schemas and SQL are written from scratch |
| [0006](0006-fanout-cost.en.md) | Extraction runs once per observing pair, not once per batch | accepted · 2026-08-31 | The spec's fan-out saving only holds if the pair's perspective is erased. The cost was chosen and recorded — N + N(N−1) per batch |
| [0007](0007-aimon-core-boundary.en.md) | The boundary with aimon-core is `PeerMemory`, and nothing else | accepted · 2026-09-04 | One type is the seam; tenancy is ours, the agent is theirs. Two addenda record wiring the contract suite and the coordinate split that followed |

If you arrived from aimon-core, read [0007](0007-aimon-core-boundary.en.md) first: it draws the
boundary between the two repositories, and its two addenda carry what actually happened afterwards.
