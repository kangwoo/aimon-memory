[한국어](0003-llm-transport.md) · **English**

# ADR 0003 — Direct HTTP to providers, not their SDKs

**Status:** accepted · 2026-08-31

## Context

The design says "official SDKs plus a thin abstraction, for control over the tool loop".

## Decision

`java.net.http.HttpClient` and Jackson, against the providers' documented REST APIs.

## Why

**The abstraction was going to exist regardless.** The design already requires one, for exactly the
reason it states: the tool loop has to be controlled here. Two SDKs behind one interface means
maintaining two translations *and* two dependency surfaces instead of two translations.

**Recording needs a boundary the SDK does not offer.** `RecordingChatBackend` wraps the provider
primitive, which is what lets a ten-step agentic loop replay step by step without the harness
modelling loops. Reaching that seam through an SDK means intercepting its HTTP layer, which is
neither stable nor supported.

**The provider surface used here is small** — chat completions with tools and structured output, plus
SSE. It is a few hundred lines per provider, all of it visible.

## Cost

New provider features need explicit support rather than arriving with a version bump. Breaking API
changes surface as test failures rather than compile errors. Both are acceptable for a surface this
narrow, and the second is what the fixture corpus is for.
