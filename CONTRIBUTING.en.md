[한국어](CONTRIBUTING.md) · **English**

# Contributing

Read it, fix it, send it. What follows is a description of how this repository actually works, not a
list of manners to observe.

---

## What you need

- **JDK 21.** `java` in `gradle/libs.versions.toml` is the only source for that number. Gradle comes
  with the wrapper, so `./gradlew` is the whole toolchain.
- **Docker.** For the local Postgres `docker compose` starts, and for the Testcontainers tier, which
  starts its own. The fast gate (`checkAll`) needs neither.

Every dependency comes from Maven Central, `at.aimon.core:aimon-core:0.2.4` included, so a fresh
clone builds. Exactly one coordinate comes from Central's snapshot repository rather than a release,
and that is remote too, so there is nothing to set up. It is described under "The contract tier"
below.

```sh
git clone https://github.com/kangwoo/aimon-memory
cd aimon-memory
docker compose up -d
./gradlew checkAll
```

---

## The gates

| Command | What it proves | Docker | When |
|---|---|:-:|---|
| `./gradlew checkAll` | Formatting, style, the BOM, the 284 tests that need no database, and the 21 contract cases | no | on every save |
| `./gradlew integrationTest` | The 318 Testcontainers tests | yes | before opening a PR |
| `./gradlew :aimon-memory-client:contractTest` | aimon-core's 21 `PeerMemory` contract cases | no | `checkAll` already calls it; see below |
| `./gradlew :aimon-memory-worker:loadTest` | Concurrent readers and writers under contention | yes | when you touch the worker or the queue |

`checkAll` and `integrationTest` are CI's two jobs and both are gates. They are separated for one
reason: so a formatting mistake does not wait behind a database.

Almost all of this system's behaviour is proven in `integrationTest`. A partial unique index, a
pgvector distance and a Flyway migration chain are not things a mock stands in for. A green
`checkAll` does not mean it works.

The timings `loadTest` prints describe the runner, not the system. Its assertions are the gate:
concurrent writers to one pair must produce no errors and a gap-free sequence, and dedup must still
converge under contention.

### The contract tier

`aimon-memory-client` runs aimon-core's five-tier `PeerMemory` contract suite by subclassing it. The
suite arrives as `at.aimon.core:aimon-memory-testkit`, and **that artifact has no release yet** — it
first ships in aimon-core 0.3.0. It is published to Central's snapshot repository instead, and the
build opens that repository for that one coordinate. So the tier **runs on any machine**: a fresh
clone and a CI runner alike.

The tier lives in a source set of its own, `src/contractTest` rather than `src/test`, which keeps an
unreleased coordinate off `aimon-memory-client`'s compile classpath. If the testkit fails to resolve
for any reason, the tier **skips itself** with one line saying why rather than failing the build.

`checkAll` names these 21 cases, so there is nothing extra to run. To run just this tier:

```sh
./gradlew :aimon-memory-client:contractTest
```

**A green CI now is evidence that the contract suite passed.** It used to not be: the testkit lived
only in someone's `~/.m2`, so CI always skipped the tier and a PR touching the adapter had to report
a hand-run result. Publishing the snapshot to Central ended that exception. The skip itself has not
gone away, though — if you see the skip line in a log, that run did not check the contract, so do
not read its green as covering it.

---

## Formatting and style

```sh
./gradlew format        # fix with Spotless
./gradlew checkFormat   # check without fixing
./gradlew checkStyle    # Checkstyle, main sources only
```

The formatter is configured in `config/eclipse/eclipse-formatter.xml` and the Checkstyle rules in
`config/checkstyle/checkstyle.xml`. Import order is `java`, `javax`, `jakarta`, `org`, `com`, then
everything else, enforced by Spotless.

Lines run to **120 characters**, and the three places that write that number down agree —
`LineLength` in `config/checkstyle/checkstyle.xml` (`package`, `import` and URLs are exempt),
`lineSplit` in `config/eclipse/eclipse-formatter.xml`, and `max_line_length` in `.editorconfig`. The
first two fail the build and the third only tells your editor, so move all three together.

Test sources are exempt from Checkstyle. They are not exempt from formatting.

---

## CI never calls a model

`AIMON_MEMORY_LLM_MODE` defaults to `replay`, and the build conventions set it on every test task. A
call with no recorded fixture fails.

```
replay   serve from test-fixtures/llm/; a miss is a failure   (the default, and what CI runs)
record   call the provider and write the fixture
live     call the provider, record nothing
```

A replay miss means a prompt changed. **Read the diff before re-recording.** Regenerating until the
suite goes quiet is the same as deleting the fixtures. Recording is a deliberate local act that needs
credentials, and `./scripts/record-fixtures.sh` is how it is done.

## Rewriting fixtures

```sh
./gradlew test -Daimon.memory.golden.update=true    # rewrite the golden fixtures instead of asserting
./gradlew test -Daimon.memory.eval.update=true      # regenerate the ranking baseline
```

A route added, or one whose shape changed, means regenerating the API description with the same
switch. This one needs a database, so it is `integrationTest` rather than `test`.

```sh
./gradlew :aimon-memory-api:integrationTest -Daimon.memory.golden.update=true
```

Both switches make the tests **pass by definition.** A diff touching those files therefore needs the
same scrutiny as the code that produced it. `test-fixtures/README.en.md` has the details.

---

## When to write an ADR

There is one specification, `docs/spec/aimon-memory-design.md` (ADR 0005). An ADR is where **the
implementation departs from it, and why, with the evidence.** Write one when:

- you went somewhere the specification did not (ADR 0004, the half-life formula, is the example);
- the structural decision would take a rewrite rather than a migration to undo;
- it is a licensing or copyright boundary (ADR 0005);
- it is a contract with another repository, especially one no build can show (ADR 0007).

Follow the existing shape. The Korean canonical file is `docs/adr/NNNN-slug.md`: a language banner on
the first line, `# ADR NNNN — 제목`, `**상태:** accepted · YYYY-MM-DD`, then 맥락 / 결정 / 결과. Its
English pair `NNNN-slug.en.md` uses `**Status:**` and Context / Decision / Consequence in the same
places. Numbers run on.

When a decision is overturned, or a fact inside one stops being true, do not rewrite the body — **add
an addendum**: `## Addendum · YYYY-MM-DD — one line`. The value of the record is that it preserves
the judgement as it stood. ADR 0007 has three.

Conversely, anything a reader can learn by reading the code is a comment, not an ADR. Comments here
record **why not the other way**, rather than what the line does. Please keep to that.

---

## Commit messages

Drawn from the history rather than invented for this page.

- **An English subject line.** No full stop, initial capital, stating what was done. Two things in
  one commit are joined with `, and` — `Build against aimon-core 0.2.4, and drop the composite build`.
- **A body that says why.** The diff already says what changed. Subheadings and length are fine; the
  commits here are mostly long, and that is the convention.
- **Say how you verified it.** "Verified against the release rather than assumed: the 0.2.4 jar was
  opened and checked …" is a real sentence from this history. If you did not run it, say you did not.
- **Say what is still missing.** "Still unwired: …" — a commit that names its own loose end.
- Documents are in Korean; **commit messages are in English.** All twelve of them are.

Every commit in the current history carries a `Claude-Session:` trailer. That is where those commits
came from, not something asked of contributors.

---

## Before opening a PR

- [ ] `./gradlew checkAll` passes
- [ ] `./gradlew integrationTest` passes (needs Docker)
- [ ] if you touched `RemotePeerMemory` or its endpoints, you checked the `checkAll` log and saw
      `contractTest` actually run rather than skip
- [ ] a new endpoint has an entry in `RoutePolicy` (without one the build fails)
- [ ] a changed golden fixture or ranking baseline is explained in the PR
- [ ] a decision that departs from the specification has an ADR
- [ ] a documentation change updates the Korean page and its `.en.md` together

## Documentation rules

Korean is canonical; the English page is the same name with `.en.md` — `CONTRIBUTING.md` and
`CONTRIBUTING.en.md`. Both carry a language banner on the first line, linked by relative path. Change
one alone and the other quietly starts saying something false, so the two move in one commit.

Paths do not move. The README, code comments, gradle comments and commit messages all call paths like
`docs/adr/0007-aimon-core-boundary.md` by name.

## Code of conduct, and security

- The [Code of Conduct](CODE_OF_CONDUCT.en.md) applies to everyone taking part.
- **Do not open an issue for a vulnerability.** Follow the private process in
  [SECURITY.en.md](SECURITY.en.md).

## Licence

Contributions are distributed under the repository's [Apache-2.0](LICENSE). Opening a PR is your
agreement to that. There is no separate CLA.
