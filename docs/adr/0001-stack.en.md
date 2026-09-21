[한국어](0001-stack.md) · **English**

# ADR 0001 — Stack

**Status:** accepted · 2026-08-31

## Decision

Java 21 · Spring Boot 3.5 MVC on virtual threads · PostgreSQL 16 + pgvector · Flyway · Gradle
multi-module · Lucene analyzers · jtokkit.

## Java 21, not 25

The plan specifies Java 25. Virtual threads are generally available from 21, which is the only
language feature the design actually depends on, and 21 is an LTS with the tooling already settled.

The version is a single line in `gradle/libs.versions.toml`. Moving to 25 is that line plus a CI
image bump; nothing in the code is written against 21 specifically.

## Spring MVC on virtual threads, not WebFlux

The design document said WebFlux. Almost every handler here is a short sequence of blocking JDBC
calls, and the one that waits a long time — the dialectic — waits on a socket. A virtual thread
parked on either costs a few hundred bytes.

WebFlux would buy the same concurrency and charge for it in every stack trace, every debugging
session, and a reactive JDBC story that does not exist. `spring.threads.virtual.enabled=true` gets
the concurrency while the code still reads top to bottom.

## Postgres alone

One store, not three. The entity layer is two ordinary tables with a foreign key, and it does what a
graph database would be brought in for. Vectors, full-text and relational integrity live in one
transaction, which is what makes "insert the conclusion, link its entities, write the audit event"
an operation that either happens or does not.

## Addendum · 2026-09-20 — Moved to Boot 4, and the "3.5" above is now false

The decision stands and is not rewritten. What this ADR chose was *Spring MVC on virtual threads,
not WebFlux*, and Boot 4 leaves both of those alone. The only thing no longer true is one number on
the decision line.

| Above | Now |
|---|---|
| "Spring Boot 3.5 MVC" | Spring Boot 4.1.1 MVC. `spring.threads.virtual.enabled=true` is unchanged. |

springdoc is the reason. springdoc 3.x leans on `spring-boot-webmvc` and `spring-boot-health`, which
do not exist on a Boot 3.5 classpath, so while this build stayed on 2.x the 3.x line was blocked in
`.github/dependabot.yml`. Boot 4 removed that pin's premise, and the two moved in the same change.

The move cost three things, all of them consequences of Boot 4 splitting its modules. The test
slices left `spring-boot-starter-test`, so a module using `@AutoConfigureMockMvc` — or
`@AutoConfigureMetrics`, which is the metrics half of the now-split `@AutoConfigureObservability` —
has to ask for that artifact by name. Jackson 3 (`tools.jackson`) became the default and Jackson 2's
auto-configuration moved into a module of its own, which took away the `ObjectMapper` that
`ChatController` injects — restored with `spring-boot-jackson2`, because seven modules in this build
speak Jackson 2 and migrating seven modules is not what a framework bump is. HTTP message conversion
stays on Boot 4's default, which is Jackson 3. And Spring Framework 7 followed RFC 9110 in renaming
422 from `UNPROCESSABLE_ENTITY` to `UNPROCESSABLE_CONTENT` — same status code, same response body,
a different constant name.

All 623 tests pass. `docs/openapi.json` did not change by a byte, and `OpenApiSpecTest` is what
confirms it.

## Addendum · 2026-09-21 — The seven modules moved, and "not migrating" above is now false

The addendum just above gave the reason for restoring Jackson 2's `ObjectMapper` bean with
`spring-boot-jackson2`: "migrating seven modules is not what a framework bump is". That was true
then. The work has since been done on its own.

| Above | Now |
|---|---|
| "restored with `spring-boot-jackson2`, because seven modules in this build speak Jackson 2" | Seven modules speak Jackson 3. `spring-boot-jackson2` is gone. |

37 Java files across 8 modules, most of it import substitution. The annotations were left alone —
Jackson 3's `jackson-databind` still depends on `com.fasterxml.jackson.core:jackson-annotations`, so
the two `@JsonIgnoreProperties` imports are unchanged. Jackson 3 made mappers immutable and dropped
`ObjectMapper.configure(...)`, so the mappers in `Json` and `OpenApiSpecTest` moved to
`JsonMapper.builder()`. `JsonProcessingException` became `JacksonException` and stopped being
checked, but the catches were retyped rather than deleted — what those blocks do is turn a Jackson
failure into this domain's `LlmException` or `StoreException`, and that is still wanted.
`JsonNode.fields()` and `fieldNames()` are gone in favour of `properties()` and `propertyNames()`,
which hand back a `Collection` rather than an `Iterator`, so two `forEachRemaining` loops became
`forEach` and a third collapsed into `declared.addAll(node.path("properties").propertyNames())`.

**One part is not substitution.** `asText()` → `asString()`, 129 sites, is not a rename. Jackson 3
**redefined the `asString`/`asInt`/`asBoolean` family from "coerce, falling back to a zero value" to
"coerce, or raise"**. Measured by running the same code against both versions' jars:

| node | Jackson 2 | Jackson 3 |
|---|---|---|
| object / array `.asText()` / `.asString()` | `""` | **`JsonNodeException`** |
| null node | `"null"` | `""` |
| object `.asText("d")` / `.asString("d")` | `""` | `"d"` |
| `"4.9".asInt()` | `4` | **raises** |
| `"hi".asInt()` / `true.asInt()` | `0` / `1` | **raises** |
| `"hi".asBoolean()` | `false` | **raises** |

`asInt`, `asDouble` and `asBoolean` kept their names, so they do not appear in this change's diff at
all — and they changed the same way. That is the easiest part of this migration to miss, and it went
unlooked-at until review.

**The strictness itself is right.** Reading a provider's wrong-shaped answer as an empty string was
the bug. What cannot stand is where the raise lands. `JsonNodeException` is a `RuntimeException` and
is not an `LlmException`, and `FallbackChatBackend.run` catches `LlmException` and nothing else — so
unwrapped, one provider's response shape drifting takes the request down instead of handing it to the
next provider. Further up it misses `ApiExceptionHandler`'s `MemoryException` branch and becomes a
500 with no code. Under Jackson 2 the same response degraded to an empty answer and nothing recorded
it. None of those three is what this system promises.

So the strictness stays and the boundaries were built. `Json.shaped` (llm) and `MemoryHttp.shaped`
(client) move Jackson's failures into the types each module already had — `LlmException("bad_json")`
and `RemoteMemoryException`. `OpenAiEmbedder` raises `EmbeddingException` rather than storing a
part-zero vector; `EvaluationSet` names the fixture. `bad_json` is not `llm_rejected`, so the
fallback chain moves on to the next attempt, which is what should happen when one provider of several
cannot be read. `ProviderShapeDriftTest` pins this, and its failover case was confirmed to fail with
the wrapping removed before it was kept.

`FAIL_ON_TRAILING_TOKENS` and `FAIL_ON_NULL_FOR_PRIMITIVES` also flipped their defaults. Both are
left flipped, with the reason written into `Json` — a model that appends a sentence to its JSON has
not answered the schema it was given, and reading half of it is how that went unnoticed.
`ChatController` injects `JsonMapper`, not `ObjectMapper`: Boot 4's XML and CBOR configurations each
define another bean assignable to `ObjectMapper`, so the wider type becomes a
`NoUniqueBeanDefinitionException` the day either arrives. `aimon-memory-engine` now declares the
annotations coordinate it imports instead of relying on it transitively.

**Jackson 2 does not leave the classpath.** That is not something this change failed to do; it is
not available. Even setting the two libraries below aside, Jackson 3's own databind depends on the
Jackson 2 annotations artifact, so no module here is free of it. springdoc reaches Jackson 2 through
`swagger-core-jakarta`, and `jjwt-jackson` has no Jackson 3 line at all. Neither asks the context for
a mapper — both build their own — so neither is a reason to bring `spring-boot-jackson2` back.
`aimon-memory-client` also carries both, because `aimon-core` brings Jackson 2 in at runtime.
aimon-core does expose Jackson 2 in public signatures — `McpTransport.sendRequest(String, JsonNode)`
and around two dozen others — but none of them is in `at.aimon.core.memory.*`, the PeerMemory
contract this module implements, and none is among the 28 aimon-core types it imports. That is what
made moving this module possible, and it is a narrower claim than "aimon-core does not expose
Jackson", which is false.

The other six (llm, store, engine, embed, recall, testkit) have no jackson-**databind** 2 on their
runtime classpath. They do all still carry `com.fasterxml.jackson.core:jackson-annotations`, because
Jackson 3's databind depends on it — as the paragraph above says it does.

`checkAll` and `integrationTest` both pass. `docs/openapi.json` again did not change by a byte, and
`OpenApiSpecTest` is what confirms it — dropping `spring-boot-jackson2` did not touch the schema
this service publishes.
