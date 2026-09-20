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
