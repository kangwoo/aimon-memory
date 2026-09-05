[한국어](0002-persistence.md) · **English**

# ADR 0002 — Spring JDBC with hand-written SQL, not jOOQ codegen

**Status:** accepted · 2026-08-31

## Context

The plan calls for jOOQ with a code generator wired to Flyway. JPA was ruled out and stays ruled
out — the composite foreign key on `(observer, observed, workspace_name)` is exactly the shape ORMs
handle worst.

## Decision

`JdbcClient` with hand-written SQL, plus a purpose-built `FilterCompiler` for the dynamic parts.

## Why

**Codegen needs a live database at build time.** Flyway runs, jOOQ introspects, generation happens,
compilation follows. That is a real bootstrap cost on every clean checkout and in CI, paid for a
type-safety benefit that the queries here mostly do not need — most are static strings against a
schema that one migration file defines.

**The dynamic query is one query.** `FilterCompiler` is the only place SQL is assembled at runtime,
it is about a hundred and fifty lines, and it does the two things that matter more than type safety:
an allowlist of filterable columns, and strict operand coercion. Both are enforced by tests
(`FilterCompilerTest`) rather than by a generated schema.

**Vectors, `jsonb` and arrays would need custom bindings anyway.** pgvector through jOOQ means a
converter and a binding per type. Through JDBC it means `?::vector` and a fifteen-line helper.

## Cost

No compile-time check that a column exists. Mitigated by column lists in one place (`Sql`), one
mapper per table (`RowMappers`), and Testcontainers coverage of every query — a renamed column fails
the suite immediately rather than at runtime.

Revisiting this is a contained change: the repositories are the only thing that would move.
