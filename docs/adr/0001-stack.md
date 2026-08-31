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
