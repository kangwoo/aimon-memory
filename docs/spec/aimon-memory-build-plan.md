# aimon-memory 구현 작업 계획

> `dyad-design.md`를 실행 가능한 단위로 분해한 작업 계획.
> 전제: **3명 · 전체 범위(P0~P6) · Spring MVC + 가상 스레드**
> 작성일: 2026-08-31

---

## 0. 전제와 타임라인

| 항목 | 값 |
|---|---|
| 인원 | 3명 (2명일 때의 축소판은 §9) |
| 범위 | P0~P6 전체 + 하드닝 |
| 런타임 | **Java 25 LTS** — 가상 스레드가 정식, preview 플래그 불필요 |
| 웹 | **Spring MVC + 가상 스레드** (`spring.threads.virtual.enabled=true`) |
| 스프린트 | 2주 |
| 캘린더 | **28주** (약 6.5개월) |

> **타임라인 정정**
> 설계 문서의 22주는 각 Phase 소요를 단순 합산한 수치로, **통합 비용과 하드닝이 빠져 있었다.**
> 3명이 병렬로 붙어도 통합 체크포인트마다 수렴 비용이 들고, 마지막 하드닝 2~3주는 생략할 수 없다.
> 아래 계획은 그 둘을 포함한 값이다.

### 마일스톤

| | 시점 | 상태 |
|---|--:|---|
| **M1** | 10주 | 메시지를 넣으면 결론이 쌓인다 (P0·P1) |
| **M2** | 18주 | **LLM 없는 조회로 실사용 가능** (P2·P3·P4) ← 여기서 한 번 릴리스 |
| **M3** | 23주 | Dialectic 동작 (P5) |
| **M4** | 28주 | Dreamer + 하드닝 완료 (P6) |

**M2가 실질적인 목표 지점이다.** M3·M4는 필요성이 확인된 뒤 진행해도 되고, 스키마가 이미 준비돼 있어 재작업이 아니다.

---

## 1. 세 가지 원칙

### 1.1 계약 우선 — 스프린트 0에 SPI를 못 박는다

3명이 병렬로 움직이려면 **서로의 구현을 기다리지 않아야** 한다. S0에서 인터페이스 시그니처를 전부 확정하고, 각 트랙은 상대 트랙의 **스텁 구현**을 상대로 개발한다.

```java
// aimon-memory-core — S0에 확정, 이후 변경은 3인 합의
public interface Analyzer {
    String analyze(String text);              // content_analyzed 생성
    List<String> tokens(String text);         // BM25용
    String languageTag();
}
public interface Embedder {
    float[] embed(String text, EmbedPurpose purpose);
    List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose);
    int dimensions();
}
public interface LlmClient {
    <T> StructuredResult<T> structured(LlmRequest req, Class<T> schema);
    ToolLoopResult toolLoop(LlmRequest req, List<ToolDef> tools, int maxIterations);
    Stream<String> stream(LlmRequest req);
}
public interface ConclusionStore {
    List<ScoredConclusion> semantic(PairKey pair, float[] q, int limit, Filter f);
    List<ScoredConclusion> keyword(PairKey pair, String analyzed, int limit, Filter f);
    DedupOutcome upsert(ConclusionDraft draft);
}
public interface EntityStore {
    List<EntityMatch> match(String workspace, float[] q, int topK);
    void link(String workspace, String entityId, String conclusionId, PairKey pair);
}
```

### 1.2 픽스처 우선 — Deriver보다 하네스를 먼저 짠다

LLM은 비결정적이라 **record/replay 하네스 없이는 회귀 테스트가 성립하지 않는다.** S0의 인수 조건에 포함한다.

```
녹화:  AIMON_MEMORY_LLM_MODE=record  → 요청·응답 쌍을 test-fixtures/llm/{hash}.json 에 저장
재생:  AIMON_MEMORY_LLM_MODE=replay  → 해시 매칭으로 재생, 미스는 테스트 실패
실행:  AIMON_MEMORY_LLM_MODE=live    → 실제 호출 (로컬·수동만)
```

해시 키는 `(model, system, messages, tools, response_format)`의 정규화 직렬화. 프롬프트를 바꾸면 미스가 나고, **그게 의도된 동작**이다 — 프롬프트 변경이 조용히 넘어가지 않는다.

### 1.3 스키마는 한 번에 — P6까지 쓸 컬럼을 S0에 전부 넣는다

`level`, `source_ids`, `confidence`, `expires_at`, `last_reinforced_at`, `conclusion_events` 를 P0 시점에 다 만든다. **채우지 않을 뿐 존재는 한다.** 나중에 Dreamer를 얹을 때 마이그레이션이 아니라 코드 추가가 되게.

예외: 인덱스는 단계별로 추가한다. 안 쓰는 HNSW 인덱스가 쓰기를 느리게 하므로.

---

## 2. 리포지토리 구조

Gradle 멀티모듈. 의존 방향은 **아래에서 위로만** — 화살표 역행 금지를 ArchUnit으로 강제한다.

```
dyad/
├── build.gradle.kts              루트: 버전 카탈로그, 공통 플러그인
├── gradle/libs.versions.toml     의존성 단일 정의
├── docker-compose.yml            postgres(pgvector) + (선택) redis
│
├── aimon-memory-core/         ← 의존 없음. 도메인 타입, SPI, PairKey, WorkUnitKey, NanoId
├── aimon-memory-testkit/      ← core. 골든 픽스처 로더, LLM replay, 스텁 구현, Testcontainers 베이스
│
├── aimon-memory-text/         ← core. Analyzer(Nori/Standard/Bigram), 정규화, jtokkit
├── aimon-memory-embed/        ← core. 임베딩 클라이언트, 배치·절단·재시도
├── aimon-memory-llm/          ← core. OpenAI/Anthropic 백엔드, 구조화 출력, 툴 루프, replay 훅
├── aimon-memory-store/        ← core, text. jOOQ 리포지토리, Flyway, pgvector, 필터 DSL
│
├── aimon-memory-recall/       ← store, text, embed. 융합 공식, BM25, 엔티티 부스트, explain
├── aimon-memory-engine/       ← store, llm, embed, text. Deriver, 중복제거, Summarizer, Dreamer
│
├── aimon-memory-worker/       ← memory, store. 큐 매니저, 컨슈머, Reconciler  [독립 실행]
└── aimon-memory-api/          ← recall, memory, store. 컨트롤러, DTO, 인증, SSE  [독립 실행]
```

`aimon-memory-worker`와 `aimon-memory-api`는 **같은 코드베이스, 다른 실행 프로파일**이다. 배포는 두 개의 프로세스.

---

## 3. 트랙 분할

| 트랙 | 담당 모듈 | 성격 |
|---|---|---|
| **A · 영속성/API** | `aimon-memory-store`, `aimon-memory-api` | 스키마, 리포지토리, CRUD, 인증, 필터 DSL, OpenAPI |
| **B · 검색/텍스트** | `aimon-memory-text`, `aimon-memory-embed`, `aimon-memory-recall` | Analyzer, BM25, 융합 공식, 엔티티 레이어, 평가셋 |
| **C · LLM/파이프라인** | `aimon-memory-llm`, `aimon-memory-engine`, `aimon-memory-worker` | 프로바이더, 프롬프트, Deriver, 큐, Dialectic, Dreamer |

`aimon-memory-core`와 `aimon-memory-testkit`은 **공동 소유**다. 변경 시 3인 리뷰.

**트랙 B가 가장 위험하다.** 정확도가 걸려 있고 튜닝 루프가 길다. 가장 경험 많은 사람을 배치한다.

---

## 4. 스프린트 계획

### S0 · 부트스트랩 — 1~2주차 · 전원 공동

목표: **아무도 서로를 기다리지 않는 상태를 만든다.**

| # | 작업 | 담당 |
|---|---|---|
| 0.1 | Gradle 멀티모듈 골격, 버전 카탈로그, Spotless/ArchUnit, GitHub Actions | A |
| 0.2 | `docker-compose` (postgres 16 + pgvector), Testcontainers 베이스 클래스 | A |
| 0.3 | **Flyway V1 — P6까지의 전체 스키마** (인덱스는 최소만) | A |
| 0.4 | `aimon-memory-core` SPI 인터페이스 6종 확정 + 도메인 타입 | 전원 |
| 0.5 | `NanoId`(21자), `PairKey`, `WorkUnitKey` 인코딩·파싱 + 테스트 | A |
| 0.6 | **LLM record/replay 하네스** + 픽스처 포맷 | C |
| 0.7 | 골든 픽스처 로더 (`aimon-memory-testkit`) + 비교 어서션 유틸 | B |
| 0.8 | 각 SPI의 스텁 구현 (`StubEmbedder`, `StubLlmClient`, …) | 전원 |

**DoD**
- CI에서 `./gradlew check` 녹색
- Testcontainers로 Postgres 띄우고 `SELECT 1` + `CREATE EXTENSION vector` 통과
- 하네스가 더미 LLM 호출을 record → replay로 재현
- 스텁만으로 각 트랙이 독립 빌드 가능

---

### S1~S2 · 기반 — 3~6주차 · 3트랙 병렬

**Track A**
| # | 작업 |
|---|---|
| A1.1 | jOOQ 코드젠 파이프라인 (Flyway → jOOQ) |
| A1.2 | 계층 리포지토리: workspace / peer / session / session_peers / messages |
| A1.3 | **복합 FK 매핑** + get-or-create 경로 (경합 시 `ON CONFLICT` 재시도) |
| A1.4 | `seq_in_session` 채번 (세션당 단조 증가, 동시성 테스트 포함) |
| A2.1 | JWT 4단 스코프 (admin/workspace/peer/session) + `exp`는 **수치형**으로 |
| A2.2 | 라우트 권한 정책 테이블 + **`allow_member_read` 허용목록 테스트** |
| A2.3 | 필터 DSL — null-safe `ne`(`IS DISTINCT FROM`), 타입 강제, 422 fail-closed |
| A2.4 | 페이지네이션 + OpenAPI 문서 생성 |

**Track B**
| # | 작업 |
|---|---|
| B1.1 | `Analyzer` SPI 구현 3종: Nori(한국어) / Standard(영어) / Bigram(폴백) |
| B1.2 | `content_norm` 정규화 (trim+lower) — **SQL 표현식과 1:1 대응 테스트** |
| B1.3 | jtokkit 래퍼 (`o200k_base`) + 토큰 카운트 |
| B2.1 | `aimon-memory-embed` — OpenAI 클라이언트, `encoding_format=float` 명시 |
| B2.2 | 배치 분할(`MAX_BATCH_SIZE`), 초과 길이 **절단**, 개별 폴백, 순서 보존 |
| B2.3 | pgvector 시맨틱 검색 (HNSW, 오버샘플 후 앱에서 중복 제거) |
| B2.4 | BM25 경로 결정 — Lucene `BM25Similarity` vs Postgres `ts_rank_cd` **벤치 후 택1** |

**Track C**
| # | 작업 |
|---|---|
| C1.1 | `aimon-memory-llm` — OpenAI 백엔드 (구조화 출력은 `create()` + `json_schema`) |
| C1.2 | Anthropic 백엔드 (툴 있으면 `{` prefill 생략) |
| C1.3 | 폴백 체인 + `AttemptPlan` (재시도가 primary로 튕겨 돌아가지 않게) |
| C1.4 | replay 훅 통합 |
| C2.1 | 큐 리포지토리 + `work_unit_key` 생성/파싱 |
| C2.2 | **`INSERT … ON CONFLICT DO NOTHING RETURNING` 클레임** + 해제 |
| C2.3 | 배치 게이팅: 토큰 512 / 30분 / **idle flush 3초** |
| C2.4 | 폴링 백오프(1→30초, ×2) + 시작 지터 + 스테일 워크유닛 청소 |

> **⛳ 통합 체크포인트 I1** (S2 종료)
> `POST /messages` → 큐 적재 → 워커가 클레임 → **스텁 LLM** 호출 → `conclusions` 저장.
> E2E 테스트 1개가 Testcontainers 위에서 통과한다.

---

### S3~S4 · 수집 완성 — 7~10주차

**Track A**
- A3.1 세션 peer 관리 API (추가/치환/제거/조회) + `joined_at`/`left_at` 시간 창
- A3.2 `observe_me` / `observe_others` 설정 계층 해석 (workspace ⊃ session ⊃ message)
- A3.3 **fan-out 대상 산출** — 관측자 목록 규칙 + 단위 테스트
- A4.1 `conclusion_events` 기록 인프라 (모든 변경 경로에서 호출)
- A4.2 메시지 배치 생성 (최대 100) + `?wait=derive` 옵션
- A4.3 결론 CRUD API (직접 주입, 목록, 삭제)

**Track B**
- B3.1 **1단 중복제거** — `content_hash` (SHA-256 of `content_norm`), 스코프 키 포함
- B3.2 **2단** — `content_norm` 일치 (정규화 규칙 차이 흡수)
- B3.3 **3단** — 코사인 ≤0.05 + 토큰 집합 스코어 (`|tokens| + 10×|고유|`)
- B4.1 `REPLACE` / `REINFORCE` 분기 + `times_derived` 승계 + `last_reinforced_at`
- B4.2 중복제거 케이스별 골든 픽스처 (경계값 포함)

**Track C**
- C3.1 **Deriver 프롬프트 자체 작성** (원본 참조 금지 — §8)
- C3.2 구조화 출력 스키마 `{conclusions:[{content, entities[]}]}`
- C3.3 메시지 포맷팅 (`YYYY-MM-DD HH:mm:ss {peer}: {content}`)
- C4.1 배치 임베딩 연동 + 결론 저장 파이프라인
- C4.2 컨슈머 디스패처 (representation / summary / reconciler / deletion)
- C4.3 실패 처리 — 에러 기록, 재시도, 부분 성공 허용

> **⛳ 통합 체크포인트 I2** (S4 종료) — **P0·P1 완료 = M1**
> 실제 LLM으로 대화 20세션을 수집해 `conclusions`가 채워지고, 모든 변경이 `conclusion_events`에 남는다.
> replay 모드로 같은 결과가 재현된다.

---

### S5~S7 · Tier 1 recall — 11~15주차 · **정확도 게이트**

가장 중요한 구간이라 3스프린트를 배정한다. Track B 주도.

**S5 — 융합 공식**
- B5.1 6개 신호 계산기: `sem` / `kw`(시그모이드) / `ent` / `reinf` / `rec` / `lvl`
- B5.2 **고정 가중치 융합** (합 1.00) + threshold를 **최종 점수**에 적용
- B5.3 `explain` 응답 필드 (신호별 값 + 가중치 + 매치 엔티티)
- B5.4 **골든 픽스처 비교 프레임** — 소수 6자리 어서션
- A5.1 `POST /recall` API + DTO + 필터 연동
- C5.1 프롬프트 버전 태깅 (엔티티 재색인 트리거용)

**S6 — 엔티티 레이어**
- B6.1 `entities` upsert — 정확 일치 → semantic top-1 & `sim ≥ 0.95`
- B6.2 `entity_links` — 워크스페이스 노드 + 쌍 스코프 엣지
- B6.3 부스트 계산 — `sim × countWeight`, `countWeight = 1/(1+0.001(n−1)²)`, `sim<0.5` 컷
- B6.4 결론 삭제 시 링크 정리 + 고아 엔티티 제거
- C6.1 Deriver 출력의 `entities`를 엔티티 파이프라인에 연결

**S7 — 프로버넌스 · 튜닝**
- B7.1 `GET /recall/provenance` — 엔티티 → 결론 → `source_ids` → 원문 메시지
- B7.2 **랭킹 평가셋 구축** — 도메인 질의 100~200개 + 정답 라벨
- B7.3 가중치 1차 튜닝 + nDCG/MRR 리포트
- A7.1 프로버넌스 API + 쌍 격리 검증
- C7.1 recall을 툴로 노출할 인터페이스 준비 (S10에서 사용)

> **⛳ 통합 체크포인트 I3** (S7 종료) — **P2·P3 완료**
> `explain` 6개 신호와 최종 score가 골든 픽스처와 **소수 6자리 일치**, 순위 뒤집힘 0건.
> 랭킹 평가셋 baseline 수립 (이후 모든 변경은 이 수치와 비교).

---

### S8~S9 · 요약 · context · 망각 — 16~18주차

- C8.1 2단 요약기 — short(20메시지) / long(60메시지), 프롬프트 자체 작성
- C8.2 요약 저장 (`sessions.internal_metadata.summaries`) + 이전 요약 포괄 규칙
- A8.1 **Tier 0 `context()`** — 토큰 예산 40/60 분배, `messages_start_id` 계산
- A8.2 `peer_target` / `peer_perspective` 표현 문자열 조립
- B9.1 감쇠 — `rec = exp(−Δdays/HALFLIFE)`, 워크스페이스 설정 노출
- B9.2 TTL — `expires_at` 도달 시 Reconciler가 소프트 삭제 + `EXPIRE` 이벤트
- B9.3 아카이브 후보 표시 (자동 삭제 없음)
- C9.1 Reconciler — 임베딩 동기화(`sync_state`), 만료 청소, 큐 정리

> **⛳ 통합 체크포인트 I4** (S9 종료) — **P4 완료 = M2 · 릴리스 지점**
> LLM 없는 조회만으로 실사용 가능. 여기서 내부 릴리스하고 실데이터를 태운다.
> **이후 모든 튜닝은 실데이터 기반으로 한다.**

---

### S10~S12 · Tier 2 Dialectic — 19~23주차 · Track C 주도

- C10.1 툴 7종 구현 — `recall`(Tier 1 노출) · `search_messages` · `grep_messages` · `messages_by_date` · `search_temporal` · `reasoning_chain` · `entity_provenance`
- C10.2 툴 루프 + 반복 상한 + 툴 출력 절단(`MAX_TOOL_OUTPUT_CHARS`)
- C10.3 **Dialectic 시스템 프롬프트 자체 작성** — 열거 절차, 갱신어 재검색, 모순 되묻기, 기권 규칙
- C11.1 추론 레벨 5단 (minimal/low/medium/high/max) + 레벨별 모델 설정·툴셋
- C11.2 토큰 예산 관리 (히스토리 상한, 입력 상한)
- C12.1 SSE 스트리밍 (`SseEmitter`, 가상 스레드)
- C12.2 `response_format` — JSON Schema 보수적 부분집합 + DoS 가드
- A12.1 `POST /chat` API + 세션 허용목록 권한 검증
- B12.1 정성 평가셋 — 열거·갱신·모순 질의 30개 + 채점 루브릭

> **⛳ 통합 체크포인트 I5** (S12 종료) — **P5 완료 = M3**
> replay 모드로 툴 호출 시퀀스가 재현되고, 정성 평가셋에서 baseline 대비 개선.

---

### S13~S15 · Dreamer — 24~26주차

- C13.1 dream 스케줄링 가드 — explicit 문서 임계 50, 8시간 간격, **부분 유니크 인덱스로 중복 방지**
- C13.2 `schedule_dream` API + 수동 트리거
- C14.1 Deduction 스페셜리스트 — `deductive` 생성 + `source_ids` 기록
- C14.2 Induction 스페셜리스트 — `inductive` + `confidence`
- C14.3 `contradiction` 생성 경로
- C15.1 peer card — 4접두사 검증(`IDENTITY:`/`ATTRIBUTE:`/`RELATIONSHIP:`/`INSTRUCTION:`), 40줄 상한, 전체 교체 방식
- C15.2 `card_refresh` 경량 dream (관측 변경 툴 없음, omni 가드 미전진)
- A15.1 peer card API + dream 상태 조회
- B15.1 **추론 트리 순회** — `get_reasoning_chain` 양방향 + GIN 인덱스 성능 확인

> **⛳ 통합 체크포인트 I6** (S15 종료) — **P6 완료**
> Dreamer의 모든 자율 변경이 `conclusion_events`로 추적 가능하고, 임의 결론의 근거를 역추적할 수 있다.

---

### S16 · 하드닝 — 27~28주차 · 전원

| # | 작업 |
|---|---|
| H1 | 부하 테스트 — 동시 수집 + 조회, 커넥션 풀·HNSW `ef_search` 튜닝 |
| H2 | 관측 — Micrometer 지표, 큐 지연·LLM 비용·recall 지연 대시보드 |
| H3 | **보안 리뷰** — 라우트 권한 정책 전수, 쌍 격리, 워크스페이스 유출 테스트 |
| H4 | 운영 런북 — 배포, 마이그레이션, 워커 스케일, 장애 대응 |
| H5 | 인덱스 최종 점검 — 안 쓰는 인덱스 제거, 실쿼리 EXPLAIN 검토 |

> **⛳ M4 — 28주**

---

## 5. 통합 체크포인트 요약

| | 주차 | 스프린트 | 인수 조건 |
|---|--:|---|---|
| **I1** | 6주 | S2 종료 | 메시지 → 큐 → 워커 → 결론 저장 E2E 1개 통과 (스텁 LLM) |
| **I2** | 10주 | S4 종료 | 실 LLM 20세션 수집, 이벤트 로그 완비, replay 재현 — **M1** |
| **I3** | 15주 | S7 종료 | **explain 6신호 소수 6자리 일치**, 랭킹 baseline 수립 |
| **I4** | 18주 | S9 종료 | LLM 없는 조회로 실사용, 내부 릴리스 — **M2** |
| **I5** | 23주 | S12 종료 | Dialectic 정성 평가 baseline 대비 개선 — **M3** |
| **I6** | 26주 | S15 종료 | Dreamer 자율 변경 전수 추적 가능 |

체크포인트마다 **3트랙이 하루 모여** 통합하고, 인수 조건 미달이면 다음 스프린트를 열지 않는다.

---

## 6. 테스트 전략

| 층 | 방법 | 게이트 |
|---|---|---|
| 단위 | JUnit 5 + AssertJ | 커버리지보다 **경계값 케이스** 우선 |
| 영속성 | Testcontainers(pgvector) | 복합 FK 위반·경합 시나리오 필수 |
| **랭킹** | **골든 픽스처 + 소수 6자리 비교** | 순위 뒤집힘 0건 |
| **랭킹 품질** | 평가셋 nDCG/MRR | I3 baseline 대비 회귀 금지 |
| LLM 경로 | record/replay | CI는 replay 전용, live는 수동 |
| 아키텍처 | ArchUnit | 모듈 의존 역행 금지 |
| 권한 | 라우트 정책 허용목록 테스트 | 신규 라우트는 명시 등록 없이는 실패 |

> **골든 픽스처는 "구현이 맞는가"를 증명하지 "가중치가 좋은가"는 증명하지 못한다.**
> 두 게이트를 반드시 분리해서 운영한다.

---

## 7. 첫 주 체크리스트

S0을 시작하는 날 바로 할 일.

```
□ 리포 생성, Java 25 툴체인 고정 (gradle/libs.versions.toml)
□ Spring Boot + spring.threads.virtual.enabled=true 로 헬스체크 1개 띄우기
□ docker-compose up → pgvector 컨테이너에서 CREATE EXTENSION vector 확인
□ Flyway V1__initial.sql 작성 — dyad-design.md §03 스키마 전체
□ jOOQ 코드젠이 Flyway 결과를 읽도록 배선
□ dyad-core에 SPI 6종 인터페이스만 먼저 커밋 → 3인 리뷰 → 머지
□ LLM replay 하네스 골격 + 픽스처 1개로 왕복 확인
□ CI: check + Testcontainers 통합 테스트 실행되게
□ 트랙별 담당자 확정, ADR 0001 (스택 결정) 기록
```

---

## 8. 리스크와 완충

| 리스크 | 징후 | 완충 |
|---|---|---|
| **랭킹 가중치가 안 맞는다** | I3는 통과하는데 실제 답이 엉뚱함 | S7에 평가셋을 만들고 **I4 이후 실데이터로 재튜닝**하는 일정을 이미 잡아둠. 가중치는 설정 외부화 |
| **한국어 형태소 품질** | Nori가 고유명사를 쪼갬 | 사용자 사전(`userDictionary`) 경로를 S1에 준비. 최악의 경우 Bigram 폴백 |
| **엔티티 추출이 프롬프트에 종속** | 프롬프트 변경 후 부스트가 흔들림 | 프롬프트 버전 태깅(C5.1) + 재색인 배치를 S6에 포함 |
| **Dialectic 프롬프트 재작성 비용** | 원본만큼 안 나옴 | S12에 정성 평가셋을 두고 반복. **원본 프롬프트를 보지 않는다**(§AGPL) |
| **트랙 B 지연이 전체를 막음** | S5~S7이 밀림 | Tier 1은 M2의 핵심이라 밀 수 없음. 대신 **S8~S9를 A·C가 먼저 착수**해 흡수 |
| **AGPL 경계 침범** | 구현 중 원본 소스 열람 | 설계 문서를 유일 명세로 삼고, 원본 저장소를 로컬에서 삭제. ADR로 기록 |

---

## 9. 2명일 때의 축소판

트랙 A와 C를 한 사람이 맡고, B를 다른 한 사람이 전담한다.

| 변경 | 내용 |
|---|---|
| 범위 | **M2(P0~P4)까지만** 1차 목표. P5·P6은 별도 결정 |
| 캘린더 | M2까지 **22~24주** |
| 순서 | S1~S2에서 Track C의 LLM 백엔드를 **OpenAI 하나만** 구현 (Anthropic은 후순위) |
| 삭제 | 정성 평가셋, Dialectic, Dreamer, 다중 프로바이더 폴백 |
| 유지 | **골든 픽스처와 replay 하네스는 그대로.** 여기를 줄이면 남는 게 없다 |

---

## 10. 부록 — 의존성 초안

```toml
# gradle/libs.versions.toml (발췌)
[versions]
java        = "25"
springBoot  = "3.5.+"      # 4.x 채택 시 함께 상향
jooq        = "3.20.+"
flyway      = "11.+"
pgvector    = "0.1.+"
lucene      = "10.+"       # nori, BM25Similarity
jtokkit     = "1.1.+"
testcontainers = "1.20.+"
archunit    = "1.3.+"

[libraries]
jooq            = { module = "org.jooq:jooq",                    version.ref = "jooq" }
flyway-pg       = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
pgvector        = { module = "com.pgvector:pgvector",            version.ref = "pgvector" }
lucene-nori     = { module = "org.apache.lucene:lucene-analysis-nori", version.ref = "lucene" }
lucene-core     = { module = "org.apache.lucene:lucene-core",    version.ref = "lucene" }
jtokkit         = { module = "com.knuddels:jtokkit",             version.ref = "jtokkit" }
openai          = { module = "com.openai:openai-java",           version = "+" }
anthropic       = { module = "com.anthropic:anthropic-java",     version = "+" }
tc-postgres     = { module = "org.testcontainers:postgresql",    version.ref = "testcontainers" }
archunit        = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
```

버전은 착수 시점에 최신 GA로 고정하고 `libs.versions.toml` 한 곳에서만 관리한다.

---

## 11. 다음 행동

1. §7 첫 주 체크리스트로 S0 시작
2. ADR 0001에 스택 결정(Java 25 / Spring MVC + 가상 스레드 / jOOQ / pgvector) 기록
3. `aimon-memory-core` SPI 6종을 첫 PR로 올려 3인 합의
4. I1(4주)을 첫 공동 목표로 설정
