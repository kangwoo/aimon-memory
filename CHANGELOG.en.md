[한국어](CHANGELOG.md) · **English**

# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

**Nothing has been released yet,** and no artifact has gone to Maven Central. The `Unreleased`
section below is drawn from `main`'s commit history, and becomes the first entry as it stands when a
first release goes out.

## [Unreleased]

### Added

- The whole pair-scoped memory system. Twelve Gradle modules, two runnable processes sharing one
  codebase (`aimon-memory-api`, `aimon-memory-worker`), three reading tiers — `context()`,
  `recall()`, `chat()`. Every fact belongs to a directed `(observer, observed)` pair, enforced by a
  composite foreign key from day one.
- Tier 1 ranking: six signals fused under fixed weights. The weights always sum to 1.00, so a
  missing signal lowers a score honestly instead of shrinking the denominator. Every response
  carries the breakdown that produced it.
- Golden fixtures (six decimal places), a labelled ranking evaluation set (nDCG/MRR/recall) and a
  qualitative dialectic set — three gates kept separate because they fail for different reasons.
- `aimon-memory-client`, the adapter implementing aimon-core's five
  `at.aimon.core.memory.PeerMemory` tiers over this service's HTTP API. Compiled to Java 17
  bytecode, and it depends on nothing else in this build.
- The pre-publish gate `:aimon-memory-client:verifyCoreIsReleased`. It refuses to publish when
  aimon-core resolved to a project or a snapshot rather than a released artifact, or when the jar it
  resolved does not contain `PeerMemory`.
- aimon-core's twenty-one five-tier contract cases, run against `RemotePeerMemory`. This is the tier
  that answers the question `RemotePeerMemoryWireTest` structurally cannot ask — whether two
  backends mean the same thing by the same call.
- `session_peer_windows` (V11). Membership is recorded append-only rather than overwritten, so a
  peer who left and came back does not lose their earlier windows.
- ADR 0007 — the decision that the boundary with aimon-core is `PeerMemory` and nothing else. It is
  written down because it is load-bearing in both directions and visible in neither build.
- `LICENSE` (Apache-2.0) at the repository root, the file `gradle.properties`' POM had been claiming.
- `NOTICE` — attribution for the ranking formula's starting point, which comes from mem0
  (Apache-2.0). The reasoning is in ADR 0005.
- `docs/openapi.json` — an OpenAPI 3.1 description of the 33 routes, their request and response
  schemas, and the error bodies. Generated from the running application and committed; `OpenApiSpecTest`
  fails when the two drift. The token scope each route needs is stamped on from `RoutePolicy` at
  generation time, so no second copy of an authorisation rule exists to disagree with the one being
  enforced. The live `/v3/api-docs` sits behind `AIMON_MEMORY_OPENAPI` and is off by default — the auth
  interceptor covers `/v1/**` only, which is what moved actuator to its own port as well. Swagger UI is
  not shipped: its webjar would be served by Boot's static mapping whatever that flag said.
- Governance documents — `CONTRIBUTING`, `CODE_OF_CONDUCT`, `SECURITY`, and this file.
- `.github/` — issue forms (bug and feature), a pull request template, dependabot, and a release
  workflow that runs on a tag push.

### Changed

- The product was renamed from `dyad` to `aimon-memory`. Packages `dev.dyad.*` →
  `at.aimon.memory.*`, configuration keys `dyad.*` → `aimon.memory.*`, environment variables
  `DYAD_*` → `AIMON_MEMORY_*`, Micrometer metric names `dyad_queue_pending` →
  `aimon_memory_queue_pending`, and the dashboard's PromQL moved with them. Modules moved under
  `modules/`.
- The build moved onto aimon-core's conventions: pre-compiled script plugins
  (`aimon.java-conventions`, `aimon.publishable`), one version catalog as the single source, and
  vanniktech maven-publish.
- `aimon-memory-client` builds against `at.aimon.core:aimon-core:0.2.4` from Central. That is the
  first release carrying the five tiers, so the composite build against a sibling checkout is gone,
  and CI no longer clones a second repository.
- **A `search` carrying a session id is now rejected.** It used to drop the session quietly and
  answer across all of them. The contract suite reversed that judgement: a filter that did not run
  must not read as one that did. The CHAT tier is the one that takes a session.
- Token minting moved from admin-only to workspace-scoped. `TokenController` refuses anything wider
  than the caller, so handing out narrow tokens no longer needs an admin key in every service.
- Workspace tuning values are validated **where they are written**. An unknown key, a weight vector
  that does not sum to 1.00, or a number outside its honourable range is a 422. Reads stay tolerant
  and log the fallback instead — one row that predates a rule must not take a workspace's recall
  down on the next request.
- The product name changed inside the specification documents too. Two names there —
  `dyad-design.md` and `dyad-core` — no longer resolved to anything.
- Documentation moved to Korean as the canonical text with an `.en.md` English companion.

### Fixed

- **Pair isolation.** A peer token could name any pair in its workspace, in a request body or a
  query string, and read back another peer's private conclusions. `AuthInterceptor` sees path
  variables and nothing else, so it could not check them. `PairScope` now builds every such key and
  checks the observer against the token, and an ArchUnit rule fails the build if a controller
  constructs a `PairKey` directly.
- The speaker in an ingest body was never checked, so a peer token could sign a message with someone
  else's name and have it derived into conclusions about them.
- The dialectic's three message tools dropped their scope predicate entirely when a chat request
  omitted the session, handing the model every message in the workspace.
- Routes where an id is the whole request (delete, the audit trail) now take the pair off the row,
  and answer not found rather than forbidden. Conclusion ids are unguessable, so distinguishing "not
  yours" from "no such row" is an oracle for enumerating another pair's rows.
- Nothing enqueued a SUMMARY unit, so no session ever got a rolling summary and `context()` always
  returned an empty one. Nothing enqueued DREAM either, and because the partial unique index counts
  pending as in flight, the pair could never dream again and the manual endpoint answered 409
  indefinitely.
- `extendClaim` had no callers, so a work unit outliving the five-minute TTL had its claim reaped
  mid-flight and was reprocessed by a second worker — twice the provider spend, racing in dedup. The
  loop now heartbeats at a third of the TTL.
- The cross-peer extraction prompt shipped three literal `%s` and substituted the wrong name in the
  fourth slot, because `.formatted` binds tighter than `+`. The self branch was correct, which is
  why single-peer tests never saw it.
- `join()` overwrote `observe_me` / `observe_others` on every ingest, so a session configured to
  observe nobody reverted to the workspace default on the next message — silently, in the direction
  of recording more.
- Entity link counts were taken across the workspace rather than within the pair, collapsing the
  `ent` signal for exactly the shared entities it exists to reward, and getting worse as unrelated
  tenants were added.
- The streaming fallback re-asked the model with no tools and none of the tool results, against a
  prompt that says it knows nothing except what the tools return — a fabricated answer after paying
  for every iteration.
- Fixture keys did not encode whether a call streams, so chat and stream recordings collided. A
  replayed stream returned an empty stream instead of raising a miss, and recording one erased the
  other.
- The Anthropic backend was written against an API that had moved on: it appended the schema to the
  system prompt and prefilled the assistant turn with an opening brace, which models from 4.6 onward
  reject with a 400. It declares `output_config.format` now.
- The reported expiry was hard-coded to twelve hours, so configuring a lifetime produced a token
  whose real `exp` and advertised `exp` disagreed, and only the one nobody looks at was right.
- Vector columns were hard-coded to 1536 while the dimension is configurable. They follow the
  setting now, and a mismatch fails startup naming both numbers rather than failing every write with
  a 409.
- An unknown session raised a bare `NoSuchElementException`, reported as a 500 and logged at ERROR —
  a caller's typo indistinguishable from a server fault.
- `observe()` discarded the `PeerView` the caller passed and rebuilt one from the peer id in the
  response. `PeerView` equality covers the whole `Principal` and a `Principal` carries a display
  name, so the observation came back with a subject unequal to the subject just handed in. The
  contract suite found it, and that an adapter can send the right bytes and parse the right bytes
  and still mean something else is the argument for running that tier at all.
- **A fresh clone did not build.** `aimonCore` was pinned to `0.3.0-SNAPSHOT`, which is not on
  Central, so `:aimon-memory-client:compileJava` failed on every machine that had not published the
  snapshot itself. aimon-core is back on the released 0.2.4, and the contract suite — whose testkit
  is on no remote repository — moved to a source set that skips itself when that coordinate does not
  resolve. The two ways that source set could go quiet — a lenient resolution swallowing failures
  other than the testkit's, and a `Test` task passing with no tests discovered — are held down by
  `verifyContractTestClasspath` and `verifyContractTestRan`. CI has no testkit and so always skips
  this tier, which means nothing outside the build would notice it going empty.

### Security

- The JWT signing key has no default. Blank, or shorter than 32 bytes, and startup fails. A
  development default in `application.yml` is a signing key published in the repository, and a
  deployment running on one is indistinguishable from a correct one.
- Token lifetimes are capped at 30 days. There is no revocation list, so expiry is the only thing
  that ends a leaked token. The configured default is checked **at startup**, not only at issue time.
- The `RoutePolicy` route allowlist and `RoutePolicyCoverageTest`. A route with no entry is refused,
  and a live handler mapping missing from the table fails the build.

[Unreleased]: https://github.com/kangwoo/aimon-memory/commits/main
