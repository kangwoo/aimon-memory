**한국어** · [English](README.en.md)

# 명세 문서 안내

이 폴더에는 규범 문서 두 건이 있다. 둘 다 한국어로 쓰여 있고, 다른 언어판이 없다.

| 문서 | 무엇인가 |
|---|---|
| [`aimon-memory-design.md`](aimon-memory-design.md) | 메모리 모델의 명세. 데이터 모델, 쓰기 경로, 3티어 읽기 경로, 융합 공식, 망각, 한국어 처리 |
| [`aimon-memory-build-plan.md`](aimon-memory-build-plan.md) | 그 명세를 무슨 순서로 어떻게 지을지에 대한 계획. 스프린트, 트랙, 게이트, 리스크 |

## 왜 이 둘만 다른 언어판이 없는가

저장소의 나머지 문서는 한국어 정본에 `.en.md` 영어판을 짝지어 둔다. 이 둘은 그러지 않는다.

[ADR 0005](../adr/0005-agpl-boundary.md) 가 이 프로젝트의 클린룸 경계를 `aimon-memory-design.md`
**한 문서**에 걸어 둔다. "명세는 이것 하나뿐이고, 구현은 이 문서만 보고 썼다"가 그 주장이다. 같은
내용의 문서가 두 언어로 존재하면 곧바로 "어느 쪽이 명세인가"가 생기고, 답이 하나여야 성립하는 주장이
약해진다. 번역본은 규범 문서를 대체하지 못한다 — 번역이 아무리 정확해도, 구현이 무엇을 보고 쓰였는지를
말할 수 있는 문서는 원본 하나뿐이다.

그래서 이 두 문서는 손대지 않는다. 대신 이 안내서가 붙는다. 아래의 절 지도와 용어 대역표는 두 문서를
읽으러 들어가기 전에 어디를 펼칠지 정하는 용도다.

**이 문서들은 2026-08-31 시점의 기록이다.** 구현이 이후 여러 자리에서 여기를 벗어났고, 벗어난 자리와
이유는 [`docs/adr/`](../adr/README.md) 에 근거와 함께 있다. 특히 [ADR 0001](../adr/0001-stack.md)(스택),
[ADR 0004](../adr/0004-half-life.md)(반감기 공식), [ADR 0006](../adr/0006-fanout-cost.md)(fan-out
비용)은 명세를 정면으로 뒤집는다. 명세와 코드가 어긋나 보이면 ADR 을 먼저 확인할 것.

## 절 지도 — `aimon-memory-design.md`

- [0. 설계 목표와 비목표](aimon-memory-design.md#0-설계-목표와-비목표) — 1일차에 사야 하는 결정, 그리고 하지 않기로 한 것
  - [목표](aimon-memory-design.md#목표) · [비목표](aimon-memory-design.md#비목표) · [라이선스 입장](aimon-memory-design.md#라이선스-입장)
- [1. 무엇을 가져오고 무엇을 버리는가](aimon-memory-design.md#1-무엇을-가져오고-무엇을-버리는가) — 두 원본 시스템에서 채택·기각한 목록
  - [신규 — 어느 쪽에도 없는 것](aimon-memory-design.md#신규--어느-쪽에도-없는-것)
- [2. 아키텍처](aimon-memory-design.md#2-아키텍처) — API·워커 두 프로세스 그림
- [3. 데이터 모델](aimon-memory-design.md#3-데이터-모델) — 전체 스키마. 쌍 키잉과 복합 외래키가 여기 있다
  - [엔티티 스코프 설계 — mem0보다 나은 지점](aimon-memory-design.md#엔티티-스코프-설계--mem0보다-나은-지점)
- [4. 쓰기 경로](aimon-memory-design.md#4-쓰기-경로)
  - [4.1 게이팅 — read-your-writes 문제의 해법](aimon-memory-design.md#41-게이팅--read-your-writes-문제의-해법)
  - [4.2 Deriver — 결론과 엔티티를 한 번에](aimon-memory-design.md#42-deriver--결론과-엔티티를-한-번에)
  - [4.3 3단 중복 제거](aimon-memory-design.md#43-3단-중복-제거) — 해시 → 정규화 → 시맨틱
  - [4.4 fan-out](aimon-memory-design.md#44-fan-out) — **구현이 여기를 벗어난다. [ADR 0006](../adr/0006-fanout-cost.md) 참고**
- [5. 읽기 경로 — 3티어](aimon-memory-design.md#5-읽기-경로--3티어)
  - [5.1 융합 공식 — mem0 공식의 결함 두 개를 고친 판](aimon-memory-design.md#51-융합-공식--mem0-공식의-결함-두-개를-고친-판) — 여섯 신호와 가중치
  - [5.2 explain — 1급 응답 필드](aimon-memory-design.md#52-explain--1급-응답-필드)
  - [5.3 엔티티 앵커 프로버넌스 — 신규 능력](aimon-memory-design.md#53-엔티티-앵커-프로버넌스--신규-능력)
  - [5.4 Tier 2 — Dialectic](aimon-memory-design.md#54-tier-2--dialectic)
- [6. 망각 — 어느 쪽에도 없는 것](aimon-memory-design.md#6-망각--어느-쪽에도-없는-것) — 감쇠와 만료. **반감기 공식은 [ADR 0004](../adr/0004-half-life.md) 에서 고쳤다**
- [7. 한국어](aimon-memory-design.md#7-한국어) — 형태소 분석과 Analyzer SPI
- [8. 검증 설계 — 이 프로젝트의 리스크를 결정하는 부분](aimon-memory-design.md#8-검증-설계--이-프로젝트의-리스크를-결정하는-부분) — 골든 픽스처와 게이트
- [9. 로드맵](aimon-memory-design.md#9-로드맵) — P0~P6 단계
- [10. 기술 스택](aimon-memory-design.md#10-기술-스택) — **[ADR 0001](../adr/0001-stack.md) 이 Java 버전과 웹 스택을 바꿨다**
- [11. 정직한 리스크](aimon-memory-design.md#11-정직한-리스크)
- [12. 요약 — 세 시스템 대조](aimon-memory-design.md#12-요약--세-시스템-대조)

## 절 지도 — `aimon-memory-build-plan.md`

- [0. 전제와 타임라인](aimon-memory-build-plan.md#0-전제와-타임라인) · [마일스톤](aimon-memory-build-plan.md#마일스톤)
- [1. 세 가지 원칙](aimon-memory-build-plan.md#1-세-가지-원칙) — 계약 우선, 픽스처 우선, 스키마는 한 번에
  - [1.1 계약 우선 — 스프린트 0에 SPI를 못 박는다](aimon-memory-build-plan.md#11-계약-우선--스프린트-0에-spi를-못-박는다)
  - [1.2 픽스처 우선 — Deriver보다 하네스를 먼저 짠다](aimon-memory-build-plan.md#12-픽스처-우선--deriver보다-하네스를-먼저-짠다)
  - [1.3 스키마는 한 번에 — P6까지 쓸 컬럼을 S0에 전부 넣는다](aimon-memory-build-plan.md#13-스키마는-한-번에--p6까지-쓸-컬럼을-s0에-전부-넣는다)
- [2. 리포지토리 구조](aimon-memory-build-plan.md#2-리포지토리-구조) — 모듈 분할의 출발점
- [3. 트랙 분할](aimon-memory-build-plan.md#3-트랙-분할)
- [4. 스프린트 계획](aimon-memory-build-plan.md#4-스프린트-계획) — S0 부터 S16 까지
  - [S0 · 부트스트랩](aimon-memory-build-plan.md#s0--부트스트랩--12주차--전원-공동) · [S1~S2 · 기반](aimon-memory-build-plan.md#s1s2--기반--36주차--3트랙-병렬) · [S3~S4 · 수집 완성](aimon-memory-build-plan.md#s3s4--수집-완성--710주차)
  - [S5~S7 · Tier 1 recall](aimon-memory-build-plan.md#s5s7--tier-1-recall--1115주차--정확도-게이트) — 정확도 게이트가 걸린 구간
  - [S8~S9 · 요약 · context · 망각](aimon-memory-build-plan.md#s8s9--요약--context--망각--1618주차) · [S10~S12 · Tier 2 Dialectic](aimon-memory-build-plan.md#s10s12--tier-2-dialectic--1923주차--track-c-주도) · [S13~S15 · Dreamer](aimon-memory-build-plan.md#s13s15--dreamer--2426주차) · [S16 · 하드닝](aimon-memory-build-plan.md#s16--하드닝--2728주차--전원)
- [5. 통합 체크포인트 요약](aimon-memory-build-plan.md#5-통합-체크포인트-요약)
- [6. 테스트 전략](aimon-memory-build-plan.md#6-테스트-전략)
- [7. 첫 주 체크리스트](aimon-memory-build-plan.md#7-첫-주-체크리스트)
- [8. 리스크와 완충](aimon-memory-build-plan.md#8-리스크와-완충)
- [9. 2명일 때의 축소판](aimon-memory-build-plan.md#9-2명일-때의-축소판)
- [10. 부록 — 의존성 초안](aimon-memory-build-plan.md#10-부록--의존성-초안) — **현재 버전은 [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) 이 정본이다**
- [11. 다음 행동](aimon-memory-build-plan.md#11-다음-행동)

## 용어 대역표

명세가 한국어로 부르는 것이 코드와 API 에서는 어떤 이름으로 나타나는지. 코드 식별자는 명세에서도 영어
그대로 쓰이므로, 이 표는 번역이 아니라 두 표기를 잇는 지도다.

| 명세의 표기 | 코드·API 의 표기 | 무엇인가 |
|---|---|---|
| (observer, observed) 쌍 | `observer`, `observed` 컬럼, `PairScope` | 모든 메모리가 귀속되는 방향 있는 단위 |
| 결론 | `conclusions` 테이블 | 오래 남는 사실 하나 |
| 등급 | `level` | 결론이 얼마나 직접적인 근거를 딛고 있는지 |
| 강화 | `times_derived`, `last_reinforced_at` | 같은 사실이 다시 도출된 횟수와 그 시점 |
| 망각 · 감쇠 | `recall.half_life_days`, `expires_at` | 강화가 멈춘 결론이 순위에서 내려가고 결국 만료되는 것 |
| 반감기 | `half_life_days` | 최신성 신호가 절반이 되는 기간 ([ADR 0004](../adr/0004-half-life.md)) |
| 여섯 신호 | `sem`, `kw`, `ent`, `reinf`, `rec`, `lvl` | 융합 점수를 만드는 항들 |
| 융합 점수 | `score`, 그리고 응답의 `explain` | 고정 가중치로 합친 최종 점수와 그 내역 |
| 3단 중복 제거 | 해시 → 정규화 → 시맨틱 | 이미 아는 사실을 다시 넣지 않는 장치 |
| 엔티티 · 엔티티 역색인 | `entities`, `entity_links` | 결론에서 뽑아낸 고유명사와 그 링크 |
| 프로버넌스 | `/recall/provenance` | 엔티티 → 결론 → 전제 → 원문 메시지 역추적 |
| 티어 0 · 1 · 2 | `context()`, `recall()`, `chat()` | 읽기 경로 셋. 아래로 갈수록 비싸고 덜 결정적이다 |
| Deriver | `DeriverService` | 메시지 묶음에서 결론과 엔티티를 뽑는 것 |
| Dialectic | Tier 2, `chat()` | 툴을 쓰는 에이전틱 읽기 경로 |
| Dreamer | dream 컨슈머 | 아무도 보지 않을 때 메모리를 정리하는 것 |
| 분석기 | `language`, Nori / Standard / bigram | 텍스트를 색인 가능한 형태로 쪼개는 것 |
| 큐 · work unit | `queue`, `work_unit_key` | 워커가 집어 가는 작업 단위와 그 직렬화 키 |
| 골든 픽스처 | `test-fixtures/golden/` | 신호와 점수를 소수 6자리까지 못 박은 기대값 |
| workspace · peer · session | 그대로 | 테넌시 계층. 번역하지 않는다 |
