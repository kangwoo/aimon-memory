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
- **A documentation site** at <https://kangwoo.github.io/aimon-memory/> — MkDocs Material with a
  Korean/English switcher and search, deployed to GitHub Pages from whatever is on `main`. The build
  runs `--strict`, so a broken cross-reference fails CI rather than reaching the published site.
  `validation.links.anchors` is turned on alongside it, because MkDocs logs a broken anchor at INFO
  by default and `--strict` would not catch it.
- ADR 0008 — the decision to gather the architecture description into one arc42 document
  (`docs/architecture.md`) and give every document a subject it owns. The module graph, design
  notes and gate lists that had accumulated in the README moved there.
- `docs/adr/` and `docs/spec/` are kept off the site. They stay in the repository, and the 80 links
  pointing into them are rewritten to GitHub URLs at render time by
  `scripts/mkdocs_github_links.py`.

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
- Four entries nothing declared came out of the version catalogue — `jooq`, `pgvector`, `nanojson`,
  and a Testcontainers BOM. The first two predate ADR 0002 deciding against them. The rule at the top
  of the file now runs both ways: if it is not there it is not used, and if nothing uses it, it is
  not there.
- **Published POMs now carry resolved versions.** No module declares a version for anything Spring's
  dependency-management supplies — that is the point of using it — so the generated POMs shipped
  those dependencies with no version at all; `aimon-memory-store` published 7 of 11 that way. A
  Gradle consumer survives on the module metadata, but a Maven consumer reads the POM and cannot
  resolve it. `versionMapping` writes what the build actually resolved, and the versionless
  dependency count is now zero across all nine published coordinates.
- **The store SPIs were sealed.** `ConclusionStore` grew from 5 methods to 16 and `EntityStore` from
  2 to 11, and recall, engine, api and worker now take those types instead of concrete classes like
  `ConclusionRepository`. Until then the claim they backed — that the store is replaceable — was not
  true: the interfaces existed and nothing called them. The rule is one line: a method called from
  outside `aimon-memory-store` belongs on an SPI. The remaining nine repositories are still concrete,
  and `SpiSurfaceTest` names those nine — an inventory rather than a todo list. `EventLog` is
  unchanged.
- **Module placement and the assembly graph were tidied.** `HashingEmbedder` moved from `engine` to
  `embed`, and `MemoryConfiguration` was split: `RecallConfiguration` is new, `AnalyzerRegistry` and
  `TextProperties` went to store, `Embedder` and `EmbedProperties` to embed. `recall` now assembles
  without `engine`. Dependencies narrowed with it — `text` came out of `api`, `llm` and `embed` came
  out of `worker`, and store's postgresql dropped from `api` to `implementation`.
- **Three public types on published modules moved or narrowed.** `Bm25.CorpusStats` is now
  `at.aimon.memory.core.model.CorpusStats` and `EntityRepository.EntityLink` is now
  `at.aimon.memory.core.model.EntityLink` — both are types the SPI passes across, and both were nested
  inside a class above it, where `core.spi` could not name them in its own signatures. `Jsonb.of`
  narrowed its return type from `PGobject` to `Object`: that was the only place a driver type appeared
  in `aimon-memory-store`'s ABI, which is why postgresql had to be `api`, which is why the driver sat
  on the compile classpath of recall, engine, api and worker. **There are no consumers** — no release,
  no tags, and the version is `0.1.0-SNAPSHOT` — so none of the three breaks anything. That is the
  reason for doing it before the first release rather than after.
- **An unknown provider name now fails at startup.** `AIMON_MEMORY_LLM_PROVIDER=openal` used to be
  treated as `none`, so a deployment started cleanly and then answered every model call with
  `llm_not_configured`. It is `unknown_llm_provider` now, and only `none` and the empty string count
  as "off". The embedder behaves the same way (`unknown_embed_provider`).
- `?tokens` is capped at 128000 (`Bounds.MAX_CONTEXT_TOKENS`). The budget is the only thing bounding
  a Tier 0 response, so an unbounded budget was an unbounded response — every other paging parameter
  here already went through `Bounds`.
- **The contract suite resolves from Central's snapshot repository.**
  `at.aimon.core:aimon-memory-testkit` has no release — 0.3.0 is the first — but its snapshot is
  published, and the build opens that repository for that one coordinate. It replaced `mavenLocal()`,
  and the difference is the point: a local publish resolves on exactly one machine, so the contract
  tier ran there and skipped everywhere else, CI included. The 21 cases now run on a fresh clone and
  on a runner. The reasoning is ADR 0007's third addendum.
- **A batch of messages goes in as one statement rather than one per row.**
  `MessageRepository.insertBatch` issued an `INSERT … RETURNING` per row in the batch. Nothing
  explodes — the batch is capped by `@Size(max = 100)` — but a hundred messages meant a hundred round
  trips and a hundred parses inside one transaction. It is one multi-row `VALUES` now. What made the
  loop look unavoidable is `RETURNING`: `id` and `created_at` are generated, the caller needs both,
  and JDBC batch execution does not hand back result sets. Postgres does — a multi-row
  `INSERT … RETURNING` is a single statement that returns every row. **No behaviour changes:** the
  same rows, in the same order, with the same columns. Seven parameters per row against the protocol
  limit of 65535 puts the ceiling at 9362 rows, and the driver refuses 9363 before the statement
  leaves the JVM — two orders of magnitude above `MessageIngestionService.MAX_BATCH`, and a loud
  failure rather than a silent one if that ever changes. **Measured** (local Testcontainers pgvector, batches of 100,
  15 rounds after 3 warm-up rounds, the two implementations alternated within each round): across
  three runs the per-row loop's median was 15.0–16.3 ms against 2.3–2.9 ms for one statement, so
  between 5× and 7× on this machine. **Those numbers describe a loopback, not a deployment.** Going from a hundred
  round trips to one should widen the gap wherever there is network latency, but that was not
  measured. What guards the regression is not a duration: `MessageBatchInsertTest` counts the
  statements.

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
- **The message constraints on an ingest request were never evaluated. They are now, and behaviour
  changes with them.** `CreateMessages.messages` was missing `@Valid`, so Bean Validation stopped at
  the list and never descended into its elements. Every `@NotBlank` on `NewMessage` was a dead letter,
  and **a message with blank or whitespace-only content was accepted with a 200 and stored.** It is a
  **400** now. The blank `peer` that appeared to be rejected was in fact caught much deeper, by the
  key encoder, and reported as `bad_key` — which is what hid the fact that this layer was doing
  nothing. **This is visible to consumers:** a client that sent empty content and got a 200 now gets a
  400. Restoring the contract the DTO already declared was the call; a stored blank message was never
  derived from or recalled anyway, it only took up room.
- **The same defect was cleared from adding session peers.** `AddSessionPeers.peers` had no `@Valid`,
  so `SessionPeerSpec.peer`'s `@NotBlank` was dead. `{"peers":[{"peer":"   "}]}` **was accepted with a
  200, and a peer row whose name was nothing but whitespace was created and joined to the session.**
  It is a 400 `bad_request` now. The one case that had been caught — `peer` as `null`, which failed
  as a 409 `constraint_violation` — now arrives as a 400 as well. **Visible to consumers.**
- **The dialectic's conversation history was the same story.** `ChatRequest.history` had no `@Valid`,
  so neither `@NotBlank` on `ChatTurn` was evaluated, and a turn with empty `content` or `role` **was
  forwarded to the model provider as-is.** Both `POST /chat` and `/chat/stream` return 400 now.
  **Visible to consumers.**
- **A blank entity name became one nameless node and poisoned the `ent` signal.** `""`, `"   "` and
  `"\t"` all normalise to the same empty key, so they did not even become several pieces of junk —
  they became **one node named nothing**, which every conclusion carrying a stray empty string then
  linked to. It has edges, so the orphan sweep keeps it; it has a vector, so it sits in the index and
  takes a slot in `entityTopK`. And because its edges reach conclusions with nothing in common, a
  query landing near its vector **hands `ent` (weight 0.13) to all of them at once.** `GET
  /recall/provenance?entity=` with whitespace returned that node with a 200.
- **The shape of that is embedder-independent; the size quoted for it is not.** On the stand-in
  embedder, three unrelated facts came back at `ent = 0.996` each — which is `countWeight` for three
  links and nothing more — with explain reporting `matchedEntities: [""]`. Seeing it took a query that
  suited it: `StubEmbedder` falls back to the token `"empty"` for text it finds no tokens in, so the
  blank node carries that word's vector, and a query for anything else scored `ent = 0.0`. Where a
  real embedder puts `embed("")` relative to real queries has **not been measured**, so how often the
  node is reached is unknown. What holds regardless is the structure: one node, kept by the orphan
  sweep because it has edges, holding a slot in `entityTopK`, and lifting everything attached to it
  together whenever it is reached.
- **The fix is in two layers, and the two layers deliberately answer differently.**
  - `EntityPipeline.linkAll` **filters them out — it does not refuse.** Every entity name in the
    system arrives through the injection endpoint, the deriver or the dreamer, and all three funnel
    through here. Refusing is wrong because most of what reaches this point is **model output**, and
    throwing would not even undo the write: `ConclusionWriter.write` is not transactional, so every
    conclusion in the batch is already committed and would simply be left **without its entity
    edges**, while the work unit is retried five times — each retry re-deriving the same facts, which
    dedup counts as reinforcement and adds to `times_derived`, inflating the `reinf` signal — before
    the batch is quarantined. `DeriverService` and `DreamerService` already skip an item whose
    **content** is blank; this is the same judgement one field over.
  - `POST /v1/workspaces/{ws}/conclusions` **refuses.** `CreateConclusion.entities` is now
    `List<@NotBlank @UsableName String>`, so it is a 400. An HTTP client can fix its own bug, and
    telling it beats dropping the name silently. It also closes `"entities": [null]`, which used to be
    a **500 `internal_error`** and is now a 400.
  - The two layers had to be made to agree on what blank means. `@NotBlank` is specified with
    `String.trim()` (characters at or below `U+0020`) and the filter uses `String.isBlank()`
    (`Character.isWhitespace`), so `entities: ["\u2000"]` was **accepted with a 200 and then dropped
    without trace** — the silent disappearance the 400 exists to prevent, arriving through the
    constraint itself. `@UsableName` is that rule, using the same method the filter does.
- **Nodes already stored are deleted, and the invariant moves into the schema (V12).** The filter
  only stops new ones: an existing nameless node has edges, so the orphan sweep keeps it, and
  `reindex` will re-embed an empty display name and put it back in the index. `V12` deletes them
  (`entity_links` cascades) and adds `ck_entity_name_norm CHECK (name_norm <> '')`. The check cannot
  fire on model output — `isUsable` is `String.isBlank()` and `normalize` is `String.strip()`, the
  same predicate — so it only speaks up when something above the database is already wrong, which is
  what `ck_level`, `ck_sync_state` and `ck_explicit_needs_session` are all for.
- That `entities` constraint is **a different kind of change from the three above.** Those made the
  runtime honour a contract the published schema already promised; this one **invents a constraint
  that was never promised**. So this time `docs/openapi.json` does change, by one line —
  `minLength: 1` on `CreateConclusion.entities.items`.
- Those two and `CreateMessages` were **the same defect in three places**: without `@Valid` on a field
  holding a list, Bean Validation stops at the list and never descends into the elements, so the
  element type's constraints are declared and never evaluated. Why it stayed invisible for so long is
  worth recording. **The published schema was right the whole time.** springdoc reads the annotations
  on the referenced type, so `docs/openapi.json` has always carried `minLength: 1` on `ChatTurn.role`,
  `ChatTurn.content` and `SessionPeerSpec.peer`. The document said the constraint was enforced; the
  runtime did not enforce it. **That is why this change touched no line of `docs/openapi.json`** — the
  implementation caught up with the document, not the other way round.
- One message's text is now bounded — `Requests.MAX_CONTENT_CHARS`, 32000 characters. The batch has
  been capped at a hundred for a long time and the message itself was not, so the size of a request —
  and of a `GET /context` **response** — was decided by whatever the largest stored message happened
  to be. 32000 characters is 8191 tokens at the usual four-characters-per-token approximation, the
  point where `OpenAiEmbedder` truncates its input: text past it is cut before it becomes a vector, so
  storing it means storing a tail that semantic recall can never see. Refusing it beats doing that
  silently. It appears in `docs/openapi.json` as `maxLength: 32000`.
- **A session roster is bounded — 100 peers per request, and more is a 400.**
  `Requests.MAX_SESSION_PEERS`. `AddSessionPeers.peers` carried only `@NotEmpty`, so the roster was
  unbounded and one request drove an unbounded number of writes: `HierarchyController` calls
  `peers.getOrCreate` (an insert and a read) and `sessionPeers.join` (an upsert and a window insert)
  per element, which is **four statements each**. A longer roster also widens the fan-out computed for
  **every** later batch of messages in that session, a cost ADR 0006 fixes at N + N(N−1) extraction
  calls. A hundred is the number the neighbouring `CreateMessages.messages` has used since it was
  written — `MessageIngestionService.MAX_BATCH` enforces the same one a layer down — and twice the
  largest room the documents discuss: the guide's worked example is a ten-person room at a hundred
  calls per batch, and the fifty-person channel is the case ADR 0006 says `observe_others` exists to
  switch off. That headroom is the point, because this cap does not bound the fan-out and cannot:
  see below.
  **This is a consumer-visible change** — a client sending a 101-peer roster used to get a 200 and now
  gets a 400. Why it is not truncated instead is the point of the bound. `Bounds` clamps the paging
  and limit parameters, and the reason it gives is that pagination tells the caller whether more
  remains; a roster has no such signal. On `PUT` truncating would be **destructive**:
  `SessionPeerRepository.replace` closes the membership of everyone not named, so the peers silently
  dropped past the hundredth would not merely fail to be added — they would be removed from the
  session and lose their access to its messages.
  This is a **new** constraint where the published schema promised nothing, the same character as
  `CreateConclusion.entities`, so `docs/openapi.json` gains one line: `maxItems: 100` on
  `AddSessionPeers.peers`. It is written `@Size(min = 1, …)` because springdoc derives `minItems` from
  `@Size` wherever it finds one, and a bare `max` would have turned the already-published
  `minItems: 1` into `0` — the same trap `NewMessage` documents one field over.
  **It bounds a request, not the roster.** `POST` adds, so a larger room can still be assembled a
  hundred at a time. That is left open deliberately: it is a rate limiter's job rather than a
  validator's.
- **`docs/openapi.json` said an empty message batch was valid — the document changed, the runtime did
  not.** `CreateMessages.messages` carries `@NotEmpty`, so `{"messages":[]}` has been a 400 since the
  record was written, and the published schema said `minItems: 0`. The culprit is swagger-core
  underneath springdoc: `ValidationAnnotationsUtils.applySizeConstraint` calls `setMinItems(min)` with
  no guard on whether anything already set it, and it runs **after** the pass `@NotEmpty` uses to set
  `minItems: 1`. So a field written `@Size(max = 100)` buys a ceiling by losing its floor. It is now
  `@Size(min = 1, max = 100)`, following `AddSessionPeers.peers`. Nothing cleaner is available:
  `@Schema(minItems = 1)` loses to the same overwrite, and dropping `@NotEmpty` would publish
  `minItems: 1` but take `required` with it and let a null array through.
  **No request starts being refused.** One line of `docs/openapi.json` changes, and a generated client
  stops having a reason to send the empty array that has always come back 400.
- **An injected conclusion's text is now bounded — `Requests.MAX_CONCLUSION_CHARS`, 800 characters,
  and more is a 400.** `CreateConclusion.content` had a floor (`@NotBlank`) and no ceiling. It is not
  the 32000 of `NewMessage.content`, because the database stops this row far earlier than the embedder
  would. `ix_concl_norm` is a **btree** over `(workspace_name, observer, observed, content_norm)`, and
  a btree entry cannot exceed 2704 bytes on an 8 KB page. Messages carry no such index —
  `ix_message_fts` and `ix_message_trgm` are both GIN, which indexes terms rather than the whole
  value. Measured against the real schema through the API, on text that does not compress: a
  conclusion of 890 Hangul characters answers 200 and 900 answers **500**; 2650 Latin characters
  answer 200 and 2680 answer **500**; a 32000-character Hangul *message* answers 200. That 500 is
  `index row size 2728 exceeds btree version 4 maximum 2704`, SQLSTATE 54000, which is not a
  `DataIntegrityViolationException` — Spring translates class 54 to
  `DataAccessResourceFailureException` — and so fell past `ApiExceptionHandler`'s constraint handler
  to the catch-all — answered as `internal_error` and logged at ERROR, putting a client's over-long
  field in the metric an outage is supposed to show up in.
  **The boundary is a range, not a number.** `index_form_tuple` compresses an attribute before it
  measures the entry, so how much text fits depends on how well that text compresses — a conclusion of
  32000 identical syllables stores without complaint. Every number here is measured on text with no
  repetition for a compressor to find, because that is the only side of the range a cap can be set
  from.
  800 is **not** 2704 divided by the three UTF-8 bytes a Hangul or CJK character costs, which would be
  901. The same entry carries the three name columns, and nothing bounds those. Measuring the worst
  case — content with no spaces to dilute the three-byte characters — against name lengths shows 800
  characters fitting alongside 255 bytes of names where 825 does not.
  **This refuses requests that used to be accepted**: a 1000-character Latin conclusion answered 200
  and now answers 400. One field cannot hold two limits, and a cap set where Latin stops would refuse
  nothing in the language this repository's corpus, prompts and specification are actually written in.
  **Three bytes per character is the worst case.** `@Size` counts UTF-16 code units, and a
  supplementary-plane character is two of them for four bytes — two bytes per counted unit, *less* than
  a Hangul syllable costs. Measured: 800 counted characters of astral text is 1600 bytes and stores,
  and "800 emoji" is 1600 counted characters that the validator answers 400 to. So the cap does bound
  the field to 2400 bytes.
  **It does not promise the row will store**, because the entry is not only this field: it is the
  content's bytes plus the three names plus fifteen bytes of tuple overhead, leaving the scope columns
  roughly 280 bytes between them at the cap — measured, 280 bytes of names stores and 285 does not —
  and nothing enforces that. `observer` and `observed` are unbounded fields of the same request body,
  so a hundred-character conclusion written into a pair with 1200-byte names is still a 500. The cap
  takes `content` out of reach of the failure; it does not close the route. Closing it means changing
  the index or guarding `ConclusionWriter`, which is the only place the deriver's own output could be
  caught and which no constraint on this field can reach. Left open, and written down.
  It appears in `docs/openapi.json` as one line: `maxLength: 800`.
- **Tier 1 recall's candidate set has a ceiling — 1000 rows per signal path, clamped rather than
  refused.** What a recall costs is `limit × oversample`, both factors were capped at 100
  independently, and **nothing looked at the product.** `RecallService` passed it straight to
  `conclusions.semantic` and `conclusions.keyword`, so ten thousand rows per path and twenty thousand
  `Conclusion` objects accumulated in a `LinkedHashMap`, then went through one `id = ANY (?)` array,
  one BM25 pass in Java, one `linksAmong` array and one sort, to return at most a hundred. The
  `Bounds` javadoc says recall is worse than linear "because the ranker oversamples each signal path by
  a multiple of it" — and that multiple was itself unbounded in the product. A thousand is the width
  this system **already** permits a recall path: `recall.entity_top_k` is validated to `[1, 1000]` in
  the same `parse()` call.
  **No previously accepted request or configuration is refused.** `recall.oversample` keeps its
  `[1, 100]` range, and `oversample: 100` is honoured in full at the default limit of 10, where it
  fetches exactly a thousand. The defaults, 10 × 4 = 40, are a twenty-fifth of the ceiling. The value
  is not rejected at the write boundary because it **can** be honoured: `bounded()` validates a
  multiplier and cannot see the limit it will be multiplied by, so a product ceiling is not
  expressible there. The response cannot report the clamp — `candidatesConsidered` is the size of the
  union the signal paths produced, not the width they were asked for — so `RecallService` logs it at
  `debug` instead. A ranking that moves with nothing in any log to say why is the failure
  `WorkspaceSettingsService` already names one module over, where an unusable configuration falls
  back and says so "since the symptom otherwise is only that tuning had no effect".
  **What does change is the result.** Where the product used to exceed a thousand, the two paths now
  see a narrower candidate set, so a workspace tuned above the ceiling can get a different ranking
  than it got before. That is the price of the bound, and it is the only behaviour this alters.
- **A fresh clone did not build.** `aimonCore` was pinned to `0.3.0-SNAPSHOT`, which is not on
  Central, so `:aimon-memory-client:compileJava` failed on every machine that had not published the
  snapshot itself. aimon-core is back on the released 0.2.4, and the contract suite — whose testkit
  was on no remote repository at the time — moved to a source set that skips itself when that
  coordinate does not resolve. The two ways that source set could go quiet — a lenient resolution swallowing failures
  other than the testkit's, and a `Test` task passing with no tests discovered — are held down by
  `verifyContractTestClasspath` and `verifyContractTestRan`. Publishing the testkit to Central's
  snapshots afterwards made the tier run everywhere, CI included (see Changed above); the skip
  remains for the case where the coordinate stops resolving.
- **A query term carrying an apostrophe or a thousands separator produced no keyword candidates at
  all.** Lucene's standard tokenizer emits `alice's` and `50,000` as single tokens, so both land in
  `content_analyzed` and in the query terms alike — but `TsQuery` stripped the punctuation and sent
  `alices` and `50000`, neither of which matches the document it was analysed from. Measured on
  Postgres 16 over a three-row corpus: 0 rows where the unstripped form finds 2, for both. That is
  every English possessive, every contraction and every grouped number. Both characters are kept
  now. The comma is simply allowlisted, because it is not a tsquery operator in any position; the
  apostrophe is kept **only between two alphanumerics**, because a term starting with one opens a
  quoted lexeme that never closes and takes the whole statement down with a syntax error. A
  word-internal `:` is still stripped — that one is a real operator no position rescues — so
  `note:draft` still fails to find its own document.

  **No previously rejected request is now accepted, and no error code changed.** What changes is
  retrieval: a query holding a possessive or a grouped number now surfaces rows the keyword path
  could never return, so the ranking of such a query can move. The golden fixtures and the ranking
  baseline did not move and had no reason to — both are indexed with the stub analyzer, which splits
  on every one of these characters, and neither corpus contains them.

### Security

- The JWT signing key has no default. Blank, or shorter than 32 bytes, and startup fails. A
  development default in `application.yml` is a signing key published in the repository, and a
  deployment running on one is indistinguishable from a correct one.
- Token lifetimes are capped at 30 days. There is no revocation list, so expiry is the only thing
  that ends a leaked token. The configured default is checked **at startup**, not only at issue time.
- The `RoutePolicy` route allowlist and `RoutePolicyCoverageTest`. A route with no entry is refused,
  and a live handler mapping missing from the table fails the build.
- `DatabaseCredentialCheck` — startup is refused when the default password committed in this
  repository is still in use against a database that is not on this host (`default_db_password`).
  Anyone who has read the repository knows that value. The local flows (`docker compose`,
  Testcontainers) are on loopback and pass unchanged.
- **A 5xx body no longer carries values the caller never sent.** The design where a
  `MemoryException`'s message becomes the response body stands — `store_failed` naming a workspace, an
  entity or a session repeats what the caller just sent, so nothing new escapes. What changed is the
  five places that put in something the caller *had* never sent. `Jsonb` returned an entire
  unparseable jsonb column (`metadata`, `configuration`, and `internal_metadata`, which is on no
  response DTO at all); `HttpSupport` and `OpenAiEmbedder` returned the provider's error body (an
  OpenAI 401 quotes the configured API key back with only its middle masked); `Json` returned 200
  characters of the model's output, which is written from a prompt built out of stored conclusions and
  messages; both streaming backends returned the provider's free-text `message`; and
  `KoreanTextAnalyzer` returned an absolute server path. All of it moved to the log — **uncut**, where
  diagnosis needs it. The body keeps only what a caller can act on: the status code, the error type.
- **A `fixture_miss` no longer hands back the prompt — on the 503 or on a 200.**
  `FixtureMissException` is a test-harness diagnostic: it names the fixtures directory as an
  absolute path and inlines the whole canonical request — the system prompt, the tool schemas, every
  turn. Replay is not opt-in, which is what makes that an API surface: `LlmMode.fromEnvironment`
  returns `REPLAY` unless `AIMON_MEMORY_LLM_MODE` or `aimon.memory.llm.mode` is set, and
  `MemoryConfiguration.llmClient` wraps every configured provider in `RecordingChatBackend`
  unconditionally — so a deployment that sets a provider and a key and leaves the mode alone
  answered every model call with that body. The exception's message is unchanged, because a failing
  `./gradlew test` is what it is written for; the split lives on `MemoryException.publicMessage`
  instead. Not in `ApiExceptionHandler`, because the handler is not the only place a message is
  copied towards a caller: a failed dream stores its message in `dreams.error` and
  `Dtos.DreamResponse` returns that column in a **200**, so a rule enforced only at the 5xx boundary
  is a rule with a second way out. Both sites now use `publicMessage`.
- **The two errors that arrive on a 200 now carry only what was written for the caller.**
  `MemoryException.publicMessageOf` handed anything that was not a `MemoryException` its own message,
  so the rule reached only the half of a `catch (RuntimeException)` that this build words itself. The
  other half is what a driver, a library or the JDK wrote for whoever reads the log. A single Postgres
  error carries the statement it was executing, the constraint and the relation, and for a not-null or
  check violation appends `Detail: Failing row contains (…)` — the row. Measured, with a dream whose
  write met a not-null column: `GET /v1/workspaces/{ws}/dreams` answered **200** with 1,462 bytes
  holding the whole INSERT, every column name, the dedup scope out of the `ON CONFLICT` clause, and the
  text, normalised form, hash and leading vector of the conclusion the dream had just derived. The
  identical exception on the HTTP path is refused by `ApiExceptionHandler.constraint` in 124 bytes —
  one door shut, the other open. Anything that is not a `MemoryException` is now `an internal failure;
  see the server log`. The same rule reaches the `sync_error` that
  `GET /v1/workspaces/{ws}/conclusions/{id}/events` returns on a 200, where a failed backfill UPDATE
  had been storing the statement and the schema. `dreams.error` was kept in the response rather than
  removed: a dream fails in the worker, so there is no 5xx and no `code` beside it, and that string is
  the only way a caller can tell `llm_not_configured` — which is theirs to fix — from a server fault.
- **The same rule reaches what a failing tool tells the model.** `DefaultLlmClient` hands a failing
  tool back to the model as a result, and the model's answer is the 200 body of `POST /chat` — the
  longest route a message takes towards a caller. The tools are recall and search, so a
  `DataAccessException` out of the store was going straight to the model. Only a `MemoryException` does
  now. Which is why the exception `ToolRegistry` raises on malformed tool arguments changed from
  `IllegalArgumentException` to a `MemoryException` (`bad_tool_arguments`): that sentence is written to
  tell the model to fix its call and try again, and the type is where a message says who it is for.
- **The log gained rather than lost.** `ReconcilerService`'s embedding-failure line now takes the
  exception itself, stack included, and `DefaultLlmClient` logs a failing tool call, which nothing did
  before — the summary went to the model and nowhere else. `queue.last_error` still stores the whole
  message: no route reads that column (`QueueRepository.QueueItem` has no error field) and the
  operator's runbook SQL is its only reader.
- The `Jsonb` case is **not reachable by a request.** Every writer into those columns is typed
  `Map<String, Object>` or `List<String>` and Postgres validates jsonb on the way in, so a body that
  survives ingress survives the read; it fires only on bytes this build did not write — an operator's
  UPDATE, a restore, a hand-written migration. It was fixed anyway because that is exactly when the
  500 gets read by someone who should not see the row. The other four are reached by an ordinary
  request the moment a provider answers with an error.

[Unreleased]: https://github.com/kangwoo/aimon-memory/commits/main
