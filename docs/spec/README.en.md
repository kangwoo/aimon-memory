[한국어](README.md) · **English**

# Guide to the specifications

Two normative documents live in this folder. Both are written in Korean, and neither has a version in
another language.

| Document | What it is |
|---|---|
| [`aimon-memory-design.md`](aimon-memory-design.md) | The memory model's specification: data model, write path, the three-tier read path, the fusion formula, forgetting, Korean handling |
| [`aimon-memory-build-plan.md`](aimon-memory-build-plan.md) | The plan for building it — sprints, tracks, gates, risks |

## Why these two are not translated

Everywhere else in this repository a Korean document is paired with an `.en.md` counterpart. Not
here.

[ADR 0005](../adr/0005-agpl-boundary.en.md) hangs this project's clean-room boundary on
`aimon-memory-design.md` being **one** document: the specification is that file, and the
implementation was written from it and from nothing else. The moment the same content exists in two
languages, "which one is the specification" becomes a live question, and the claim only holds while
the answer is a single file. A translation cannot stand in for a normative document — however
faithful it is, only the original can say what the implementation was written against.

So the two documents are left alone and this guide is added instead. The section maps below let a
reader who does not read Korean decide which part to open — with a machine translator, or with the
term table at the end.

**They are a record of 2026-08-31.** The implementation has since departed from them in several
places, and every departure is recorded with its evidence in [`docs/adr/`](../adr/README.en.md).
[ADR 0001](../adr/0001-stack.en.md) (the stack), [ADR 0004](../adr/0004-half-life.en.md) (the
half-life formula) and [ADR 0006](../adr/0006-fanout-cost.en.md) (fan-out cost) contradict the
specification outright. Where the specification and the code disagree, check the ADRs first.

## Section map — `aimon-memory-design.md`

| § | Korean heading | In English |
|---|---|---|
| 0 | [설계 목표와 비목표](aimon-memory-design.md#0-설계-목표와-비목표) | Goals and non-goals, plus the licence position |
| 1 | [무엇을 가져오고 무엇을 버리는가](aimon-memory-design.md#1-무엇을-가져오고-무엇을-버리는가) | What is taken from each source system, what is dropped, and what is new to neither |
| 2 | [아키텍처](aimon-memory-design.md#2-아키텍처) | The two-process picture: API and worker |
| 3 | [데이터 모델](aimon-memory-design.md#3-데이터-모델) | The whole schema. Pair keying and the composite foreign key are here |
| 4 | [쓰기 경로](aimon-memory-design.md#4-쓰기-경로) | Write path: [4.1 gating](aimon-memory-design.md#41-게이팅--read-your-writes-문제의-해법), [4.2 the Deriver](aimon-memory-design.md#42-deriver--결론과-엔티티를-한-번에), [4.3 three-stage dedup](aimon-memory-design.md#43-3단-중복-제거), [4.4 fan-out](aimon-memory-design.md#44-fan-out) — **the implementation departs at 4.4, see [ADR 0006](../adr/0006-fanout-cost.en.md)** |
| 5 | [읽기 경로 — 3티어](aimon-memory-design.md#5-읽기-경로--3티어) | Read path: [5.1 the fusion formula](aimon-memory-design.md#51-융합-공식--mem0-공식의-결함-두-개를-고친-판), [5.2 explain](aimon-memory-design.md#52-explain--1급-응답-필드), [5.3 entity-anchored provenance](aimon-memory-design.md#53-엔티티-앵커-프로버넌스--신규-능력), [5.4 Tier 2 Dialectic](aimon-memory-design.md#54-tier-2--dialectic) |
| 6 | [망각](aimon-memory-design.md#6-망각--어느-쪽에도-없는-것) | Decay and expiry. **The half-life formula was corrected in [ADR 0004](../adr/0004-half-life.en.md)** |
| 7 | [한국어](aimon-memory-design.md#7-한국어) | Morphological analysis and the Analyzer SPI |
| 8 | [검증 설계](aimon-memory-design.md#8-검증-설계--이-프로젝트의-리스크를-결정하는-부분) | Golden fixtures and the gates — the section that decides this project's risk |
| 9 | [로드맵](aimon-memory-design.md#9-로드맵) | Phases P0–P6 |
| 10 | [기술 스택](aimon-memory-design.md#10-기술-스택) | **[ADR 0001](../adr/0001-stack.en.md) changed the Java version and the web stack** |
| 11 | [정직한 리스크](aimon-memory-design.md#11-정직한-리스크) | Risks, stated plainly |
| 12 | [요약 — 세 시스템 대조](aimon-memory-design.md#12-요약--세-시스템-대조) | Summary table comparing the three systems |

## Section map — `aimon-memory-build-plan.md`

| § | Korean heading | In English |
|---|---|---|
| 0 | [전제와 타임라인](aimon-memory-build-plan.md#0-전제와-타임라인) | Assumptions, timeline and [milestones](aimon-memory-build-plan.md#마일스톤) |
| 1 | [세 가지 원칙](aimon-memory-build-plan.md#1-세-가지-원칙) | [Contracts first](aimon-memory-build-plan.md#11-계약-우선--스프린트-0에-spi를-못-박는다), [fixtures before the Deriver](aimon-memory-build-plan.md#12-픽스처-우선--deriver보다-하네스를-먼저-짠다), [the whole schema in sprint 0](aimon-memory-build-plan.md#13-스키마는-한-번에--p6까지-쓸-컬럼을-s0에-전부-넣는다) |
| 2 | [리포지토리 구조](aimon-memory-build-plan.md#2-리포지토리-구조) | Where the module split started |
| 3 | [트랙 분할](aimon-memory-build-plan.md#3-트랙-분할) | How the work divides into parallel tracks |
| 4 | [스프린트 계획](aimon-memory-build-plan.md#4-스프린트-계획) | S0 through S16, including [S5–S7 Tier 1 recall](aimon-memory-build-plan.md#s5s7--tier-1-recall--1115주차--정확도-게이트), the stretch carrying the accuracy gate |
| 5 | [통합 체크포인트 요약](aimon-memory-build-plan.md#5-통합-체크포인트-요약) | Integration checkpoints |
| 6 | [테스트 전략](aimon-memory-build-plan.md#6-테스트-전략) | Test strategy |
| 7 | [첫 주 체크리스트](aimon-memory-build-plan.md#7-첫-주-체크리스트) | First-week checklist |
| 8 | [리스크와 완충](aimon-memory-build-plan.md#8-리스크와-완충) | Risks and buffers |
| 9 | [2명일 때의 축소판](aimon-memory-build-plan.md#9-2명일-때의-축소판) | The reduced plan for a team of two |
| 10 | [부록 — 의존성 초안](aimon-memory-build-plan.md#10-부록--의존성-초안) | A draft dependency list. **[`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) is the current source of truth** |
| 11 | [다음 행동](aimon-memory-build-plan.md#11-다음-행동) | Next actions |

## Term table

What the specification calls a thing in Korean, and the name the same thing carries in the code and
the API. Code identifiers are English in the specification too, so this maps two notations rather
than translating one.

| In the specification | In the code / API | What it is |
|---|---|---|
| (observer, observed) 쌍 | `observer`, `observed`, `PairScope` | the directed unit every memory belongs to — a *pair* |
| 결론 | the `conclusions` table | one durable fact |
| 등급 | `level` | how directly a conclusion rests on evidence |
| 강화 | `times_derived`, `last_reinforced_at` | how often the same fact was re-derived, and when |
| 망각 · 감쇠 | `recall.half_life_days`, `expires_at` | forgetting: a conclusion that stops being reinforced slides down the ranking and eventually expires |
| 반감기 | `half_life_days` | the half-life of the recency signal ([ADR 0004](../adr/0004-half-life.en.md)) |
| 여섯 신호 | `sem`, `kw`, `ent`, `reinf`, `rec`, `lvl` | the six terms that make the fused score |
| 융합 점수 | `score`, and `explain` in the response | the fused score under fixed weights, and its breakdown |
| 3단 중복 제거 | hash → normalisation → semantic | three-stage dedup |
| 엔티티 · 엔티티 역색인 | `entities`, `entity_links` | entities extracted from conclusions, and the inverted index over them |
| 프로버넌스 | `/recall/provenance` | entity → conclusion → premise → original message |
| 티어 0 · 1 · 2 | `context()`, `recall()`, `chat()` | the three read tiers, each costlier and less deterministic than the last |
| Deriver | `DeriverService` | extracts conclusions and entities from a batch of messages |
| Dialectic | Tier 2, `chat()` | the agentic read path, with tools |
| Dreamer | the dream consumer | edits memory when nobody is watching |
| 분석기 | `language`, Nori / Standard / bigram | the analyzer that splits text for indexing |
| 큐 · work unit | `queue`, `work_unit_key` | the unit a worker claims, and the key it serialises on |
| 골든 픽스처 | `test-fixtures/golden/` | expected signals and scores, pinned to six decimal places |
| workspace · peer · session | unchanged | the tenancy hierarchy; not translated in either direction |
