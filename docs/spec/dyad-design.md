# Dyad — Honcho 골격 위에 mem0의 강점을 이식한 메모리 모델

> `honcho-java-spec.md`의 아키텍처를 뼈대로, `mem0-java-spec.md`의 검색 엔진과 운영 장치를 이식한 독립 설계.
> `Dyad`는 작업용 이름이다 — 모든 메모리가 **(observer, observed) 쌍**에 귀속된다는 핵심을 가리킨다.
> 작성일: 2026-08-31

---

## 0. 설계 목표와 비목표

### 목표

1. **되돌릴 수 없는 결정을 1일차에 산다** — peer 쌍 키잉, 복합 FK 테넌시, 결론 등급. 나중에 못 넣는 것들.
2. **기본 조회 경로에서 LLM을 부르지 않는다** — 값싸고 빠르고 **결정적**이어야 한다.
3. **포팅·회귀를 수치로 증명할 수 있어야 한다** — 골든 픽스처가 성립하는 구조.
4. **에이전틱 회상은 선택지로 남긴다** — 대체가 아니라 상위 티어.
5. **한국어가 1급 시민** — 형태소 분석이 플러그인이지 하드코딩이 아니다.

### 비목표

- Honcho API 호환 (호환하려면 클린룸의 이점이 사라진다)
- mem0 API 호환
- 그래프 DB 도입 (엔티티 레이어가 그 자리를 관계형으로 메운다)
- Surprisal 트리, Scope, 절차 메모리

### 라이선스 입장

Honcho 서버는 AGPL-3.0이다. **아키텍처·개념을 참조한 새 설계는 파생저작물이 아니지만**, 코드나 프롬프트 문자열을 옮기면 전염된다. 이 문서는 명세만 기술하며 프롬프트는 직접 작성한다는 전제다. mem0는 Apache-2.0이라 스코어링 공식을 그대로 가져와도 무방하다 — 실제로 그렇게 한다.

---

## 1. 무엇을 가져오고 무엇을 버리는가

| 층 | 채택 | 출처 |
|---|---|---|
| workspace › peer › session 계층 | ✔ | Honcho |
| **(observer, observed) 쌍 키잉** | ✔ | Honcho |
| 복합 FK 멀티테넌시 | ✔ | Honcho |
| 결론 등급 + `source_ids` 추론 트리 | ✔ | Honcho |
| 3단 중복 제거 (해시 → 정규화 → 시맨틱) | ✔ | Honcho + mem0 |
| 큐 + `work_unit_key` 직렬화 | ✔ | Honcho |
| 2단 요약 + `context()` 토큰 예산 | ✔ | Honcho |
| Dialectic 툴 루프 (선택 티어) | ✔ | Honcho |
| Dreamer (선택) | ✔ | Honcho |
| **하이브리드 스코어링 (semantic + BM25 + entity)** | ✔ | **mem0** |
| **엔티티 역색인 + 부스트** | ✔ | **mem0** |
| **감사 이벤트 로그** | ✔ | **mem0** |
| **TTL / 만료** | ✔ | **mem0** |
| **골든 픽스처 검증 체계** | ✔ | **mem0** |
| 3-스토어 분리 | ✕ Postgres 단일 | — |
| `user_id`/`agent_id`/`run_id` 3종 스코프 | ✕ peer + session이 흡수 | — |
| Scope, Surprisal, 절차 메모리 | ✕ | — |

### 신규 — 어느 쪽에도 없는 것

| 항목 | 왜 가능한가 |
|---|---|
| **`times_derived`를 랭킹 신호로** | Honcho는 재도출 횟수를 세지만 랭킹에 안 쓴다. mem0는 랭킹 기계가 있지만 이 데이터가 없다. 이으면 공짜 신호가 하나 생긴다 |
| **엔티티 앵커 프로버넌스** | mem0의 엔티티 역색인 + Honcho의 추론 트리를 체이닝하면 `엔티티 → 결론 → 전제 → 원문 메시지`를 **LLM 없이** 역추적할 수 있다 |
| **망각(decay)** | `times_derived` + `last_reinforced_at` + `expires_at` 조합. 강화가 멈춘 결론은 랭킹에서 서서히 내려가고 결국 만료된다. 둘 다 안 잊는다 |
| **결론 변경 감사 로그** | Dreamer가 자율적으로 결론을 지우고 바꾸는 순간 감사 추적이 **필수**가 된다. Honcho에는 없다 |
| **추출 호출에서 엔티티를 공짜로** | mem0는 엔티티 추출을 별도로 한다. Dyad는 이미 도는 deriver 출력 스키마에 필드 하나를 더해 얻는다 (§4.2) |

---

## 2. 아키텍처

```
┌────────────────────────────────────────────────────────────┐
│  API                                                        │
│  · 메시지 수집: 저장 + 큐 적재 후 즉시 반환                    │
│  · Tier 0 context()  — LLM 0회                              │
│  · Tier 1 recall()   — LLM 0회   ★ 새로 만드는 핵심          │
│  · Tier 2 chat()     — LLM 1~10회 (선택)                     │
└──────────────────┬─────────────────────────────────────────┘
                   │  PostgreSQL 16 + pgvector  (+ Redis 선택)
┌──────────────────┴─────────────────────────────────────────┐
│  Worker                                                     │
│  · Deriver     — 배치당 LLM 1회, 결론 + 엔티티 동시 추출      │
│  · Summarizer  — 2단 요약                                    │
│  · Dreamer     — 연역·귀납·모순 (선택)                        │
│  · Reconciler  — 임베딩 동기화, 만료 청소, 큐 정리            │
└────────────────────────────────────────────────────────────┘
```

Honcho의 2-프로세스 분리를 유지한다. HTTP를 LLM으로 블로킹하지 않는다는 철칙도 그대로다.

---

## 3. 데이터 모델

```sql
-- ── 계층 (Honcho 그대로) ─────────────────────────────────────
workspaces, peers, sessions, session_peers, messages
-- session_peers는 joined_at / left_at 시간 창을 유지한다.
-- messages는 seq_in_session, token_count를 유지한다.

-- ── 결론 — Honcho documents + mem0 payload 병합 ──────────────
CREATE TABLE conclusions (
  id                 TEXT PRIMARY KEY,              -- nanoid(21)
  workspace_name     TEXT NOT NULL REFERENCES workspaces(name),
  observer           TEXT NOT NULL,
  observed           TEXT NOT NULL,
  session_name       TEXT,                          -- explicit은 NOT NULL 강제

  content            TEXT NOT NULL,
  content_norm       TEXT NOT NULL,                 -- trim + lower       [dedup]
  content_analyzed   TEXT NOT NULL,                 -- 형태소 분석 결과    [BM25]  ← mem0
  content_hash       CHAR(64) NOT NULL,             -- SHA-256(content_norm) ← mem0

  level              TEXT NOT NULL DEFAULT 'explicit',
                     -- explicit | deductive | inductive | contradiction   ← Honcho
  confidence         REAL,                          -- inductive를 1급 필드로   ← 신규
  source_ids         JSONB,                         -- 추론 트리            ← Honcho
  message_ids        BIGINT[],

  times_derived      INTEGER NOT NULL DEFAULT 1,    -- 강화 카운트          ← Honcho
  last_reinforced_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 감쇠 계산용    ← 신규

  embedding          vector(1536),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at         TIMESTAMPTZ,                   -- TTL                 ← mem0
  deleted_at         TIMESTAMPTZ,                   -- 소프트 삭제          ← Honcho
  sync_state         TEXT NOT NULL DEFAULT 'pending',

  FOREIGN KEY (observer, observed, workspace_name)
    REFERENCES collections(observer, observed, workspace_name),
  CONSTRAINT explicit_needs_session
    CHECK (level <> 'explicit' OR session_name IS NOT NULL)
);

CREATE INDEX ix_concl_hnsw ON conclusions
  USING hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64);
CREATE INDEX ix_concl_fts ON conclusions
  USING gin (to_tsvector('simple', content_analyzed));   -- 언어 중립 + 사전 분석
CREATE INDEX ix_concl_pair ON conclusions (workspace_name, observer, observed)
  WHERE deleted_at IS NULL;
CREATE INDEX ix_concl_hash ON conclusions (workspace_name, observer, observed, content_hash)
  WHERE deleted_at IS NULL;
CREATE INDEX ix_concl_tree ON conclusions USING gin (source_ids);

-- ── 엔티티 — mem0 아이디어, 워크스페이스 노드 + 쌍 스코프 엣지 ──
CREATE TABLE entities (
  id             TEXT PRIMARY KEY,
  workspace_name TEXT NOT NULL REFERENCES workspaces(name),
  name_norm      TEXT NOT NULL,        -- 소문자 + 공백 정규화
  name_display   TEXT NOT NULL,
  kind           TEXT,                 -- PERSON|ORG|PLACE|PRODUCT|TOPIC|IDENTIFIER
  embedding      vector(1536),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (workspace_name, name_norm)
);
CREATE INDEX ix_entity_hnsw ON entities
  USING hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64);

CREATE TABLE entity_links (
  workspace_name TEXT NOT NULL,
  entity_id      TEXT NOT NULL,
  conclusion_id  TEXT NOT NULL,
  observer       TEXT NOT NULL,        -- 링크가 쌍 스코프를 들고 다닌다
  observed       TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_name, entity_id, conclusion_id)
);
CREATE INDEX ix_elink_entity ON entity_links (workspace_name, entity_id, observer, observed);

-- ── 감사 로그 — mem0 history의 확장판 ────────────────────────
CREATE TABLE conclusion_events (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_name TEXT NOT NULL,
  conclusion_id  TEXT NOT NULL,
  event          TEXT NOT NULL,   -- ADD|REINFORCE|REPLACE|DELETE|EXPIRE|RESTORE
  actor          TEXT NOT NULL,   -- deriver|dreamer|dedup|api|reconciler
  before_content TEXT,
  after_content  TEXT,
  detail         JSONB,           -- 유사도, 점수, dream_id 등
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cevent_concl ON conclusion_events (workspace_name, conclusion_id, created_at DESC);
```

### 엔티티 스코프 설계 — mem0보다 나은 지점

mem0는 엔티티를 `user_id`로 스코프해서 **사용자마다 "서울"을 중복 저장**한다.
Dyad는 엔티티 노드를 워크스페이스 단위로 두고 **엣지가 쌍 스코프를 들고 다닌다.**

```
entities         : 워크스페이스에 "서울" 하나
entity_links     : (서울 → 결론#1, observer=alice, observed=alice)
                   (서울 → 결론#7, observer=bot,   observed=bob)
```

- 임베딩·정규화 비용을 엔티티당 1회만 낸다
- 검색 시 `WHERE observer=? AND observed=?`로 쌍 격리가 그대로 유지된다
- 나중에 `entity_relations` 테이블 하나만 얹으면 **그래프 메모리로 확장**된다 — Neo4j 없이

---

## 4. 쓰기 경로

### 4.1 게이팅 — read-your-writes 문제의 해법

Honcho의 최대 약점은 결론이 최대 30분 뒤에 보인다는 것이다. Dyad는 게이트를 하나 추가한다.

```
워크유닛을 집는 조건 (OR):
  ① 누적 token_count ≥ 512                 ← Honcho
  ② 가장 오래된 아이템이 30분 초과           ← Honcho
  ③ 마지막 메시지 이후 3초간 신규 유입 없음   ← 신규: idle flush
```

**③이 대화형 UX를 살린다.** 사람은 말하고 잠깐 쉰다. 그 정지 구간에 배치가 flush된다.
부하가 높을 때는 ①이 먼저 걸려 배치 이점이 유지된다.

추가로 요청 단위 강제 옵션을 둔다.

```
POST /messages?wait=derive     결론 도출까지 블로킹 (타임아웃 상한 있음)
POST /messages                 기본 — 즉시 반환
```

Honcho의 `FLUSH_ENABLED`는 전역 스위치라 켜면 배치 이점을 통째로 잃는다. 요청 단위로 내리는 게 맞다.

### 4.2 Deriver — 결론과 엔티티를 한 번에

핵심 절약이다. mem0는 엔티티 추출을 별도 단계로 돌리지만, Dyad는 **이미 도는 추출 호출의 출력 스키마를 넓힌다.**

```jsonc
// 구조화 출력 스키마
{
  "conclusions": [
    { "content": "alice는 서울 강남에서 일한다",
      "entities": ["서울", "강남"] }         // ← 필드 하나 추가로 엔티티를 공짜로
  ]
}
```

한국어에서 특히 중요하다. mem0의 엔티티 추출은 **대문자·따옴표 휴리스틱**이라 한국어에서 사실상 무력한데,
LLM에게 시키면 언어에 무관하고 추가 호출도 없다.

### 4.3 3단 중복 제거

```
1단  content_hash 완전 일치        → times_derived += 1, last_reinforced_at = now
     (workspace, observer, observed, level, [explicit이면 session])  스코프

2단  content_norm 일치             → 1단과 동일 처리
     (해시 충돌이 아니라 정규화 규칙 차이를 흡수)

3단  코사인 거리 ≤ 0.05 이면서 같은 level (explicit이면 같은 session)
     토큰 집합 스코어로 정보량 비교:
       score = |tokens| + 10 × |고유 토큰|
     새 것이 크거나 같으면 → 기존 소프트 삭제 + 교체, times_derived 승계  [REPLACE]
     아니면                → 기존 강화, 새 것 폐기                        [REINFORCE]

모든 분기가 conclusion_events에 기록된다.
```

Honcho의 2단(정규화 + 시맨틱)에 mem0의 해시 1단을 앞에 붙였다. 해시가 값싼 필터라 대부분을 먼저 걸러낸다.

### 4.4 fan-out

관측자 목록 산출은 Honcho 그대로다. `observe_me` / `observe_others`, 그리고 **LLM 호출 1회 · 저장 N개 컬렉션**.

---

## 5. 읽기 경로 — 3티어

| 티어 | 이름 | LLM | 지연 | 결정적 | 용도 |
|---|---|---|---|---|---|
| 0 | `context()` | 0회 | ~50ms | ✔ | 프롬프트에 넣을 요약 + 최근 메시지 |
| 1 | **`recall()`** | 0회 | ~100ms | ✔ | **구조화된 사실 조회 — 새로 만드는 핵심** |
| 2 | `chat()` | 1~10회 | 수 초 | ✕ | 열거·모순·서술형 질의 |

**Tier 1이 이 설계의 존재 이유다.** Honcho에는 이 계층이 없다 — Dialectic이 그 자리를 대신하느라
간단한 조회에도 LLM을 태운다. mem0의 가장 좋은 부분이 정확히 여기다.

### 5.1 융합 공식 — mem0 공식의 결함 두 개를 고친 판

신호는 각각 독립적으로 [0,1]로 정규화한다.

```
sem   = 코사인 유사도                                         [0,1]
kw    = sigmoid(BM25_raw; midpoint, steepness)                [0,1]   ← mem0
        질의 토큰 수로 파라미터 선택:
          ≤3→(5.0,0.7)  4-6→(7.0,0.6)  7-9→(9.0,0.5)
          10-15→(10.0,0.5)  16+→(12.0,0.5)
ent   = max over 매치된 엔티티 (sim × countWeight)            [0,1]   ← mem0
        countWeight = 1 / (1 + 0.001 × (n-1)²)   n = 연결된 결론 수
        sim < 0.5 는 하드 컷
reinf = 1 − 1/(1 + ln(1 + times_derived))                     [0,1]   ← 신규
rec   = exp(−Δdays / HALFLIFE_DAYS)                           [0,1]   ← 신규
lvl   = { explicit:1.0, deductive:0.9, inductive:0.8,
          contradiction:0.6 }                                 [0,1]   ← 신규
```

```
score = 0.50·sem + 0.22·kw + 0.13·ent + 0.08·reinf + 0.05·rec + 0.02·lvl
```

**mem0 대비 고친 두 가지:**

| mem0의 결함 | Dyad |
|---|---|
| `threshold`가 **시맨틱 점수만** 자른다 → BM25가 아무리 강해도 시맨틱이 낮으면 소멸 | **최종 융합 점수**에 threshold를 적용한다 |
| 분모가 신호 유무에 따라 변한다 (1.0 / 2.0 / 2.5) → 스토어를 바꾸면 점수가 재스케일 | **가중치 합 = 1.00 고정.** 없는 신호는 0을 기여할 뿐 분모가 안 변한다 |

두 번째가 특히 중요하다. 신호가 빠지면 점수가 정직하게 낮아질 뿐 **후보 간 순위는 보존**되고,
설정이 달라도 점수를 절대값으로 비교할 수 있다.

### 5.2 explain — 1급 응답 필드

```jsonc
{
  "id": "...", "content": "alice는 서울 강남에서 일한다",
  "score": 0.7412,
  "explain": {
    "sem": 0.8210, "kw": 0.6033, "ent": 0.4500,
    "reinf": 0.3010, "rec": 0.9048, "lvl": 1.0,
    "weights": [0.50,0.22,0.13,0.08,0.05,0.02],
    "matched_entities": ["서울"]
  }
}
```

mem0의 `explain=true`를 **기본 제공**으로 승격한다. 이게 골든 픽스처 테스트의 표면이고,
튜닝할 때 어느 신호가 순위를 만들었는지 보는 유일한 방법이다.

### 5.3 엔티티 앵커 프로버넌스 — 신규 능력

두 시스템 어디에도 없는 조회다.

```
GET /recall/provenance?entity=서울&observer=alice&observed=alice

entity "서울"
  └ entity_links (observer=alice, observed=alice)
      └ conclusions
          └ source_ids →  전제 결론들
              └ message_ids → 원문 메시지 + 앞뒤 문맥
```

"왜 alice가 서울에서 일한다고 생각하는가"를 **LLM 0회로** 원문까지 역추적한다.
mem0는 엔티티 역색인이 있지만 추론 트리가 없고, Honcho는 트리가 있지만 엔티티 앵커가 없다.

### 5.4 Tier 2 — Dialectic

Honcho의 툴 루프를 유지하되 툴 하나를 교체한다.

| Honcho 툴 | Dyad |
|---|---|
| `search_memory` (시맨틱 단독) | **`recall`** — Tier 1 전체를 툴로 노출 |
| `search_messages`, `grep_messages`, 날짜 범위, 시간 필터 시맨틱 | 유지 |
| `get_reasoning_chain` | 유지 |
| — | **`entity_provenance`** 추가 |

**Tier 1을 툴로 주면 Dialectic의 반복 횟수가 줄어든다.** 한 번의 툴 호출이 더 좋은 후보를 돌려주기 때문이다.
목표는 Dialectic을 없애는 게 아니라 **호출 빈도와 반복 횟수를 낮추는 것**이다.

---

## 6. 망각 — 어느 쪽에도 없는 것

두 시스템 모두 잊지 않는다. mem0는 `expiration_date`가 있지만 사용자가 직접 넣어야 하고,
Honcho는 만료 개념 자체가 없다.

```
랭킹 감쇠:  rec = exp(−Δdays / HALFLIFE_DAYS)        기본 반감기 180일
            강화될 때마다 last_reinforced_at 갱신 → 자주 언급되는 사실은 안 늙는다

하드 만료:  expires_at 도달 시 Reconciler가 소프트 삭제 + EXPIRE 이벤트
            explicit은 기본 무기한, 명시 지정만 만료
            inductive는 기본 TTL 부여 (패턴은 낡는다)

정리:       times_derived = 1 이고 1년간 재강화 없고 아무 source_ids도 참조하지 않는
            explicit 결론은 아카이브 후보로 표시 (자동 삭제는 하지 않는다)
```

`last_reinforced_at`이 핵심이다. **재도출이 곧 갱신**이므로, 계속 언급되는 사실은 늙지 않고
한 번 말하고 만 사실은 서서히 순위에서 내려간다.

---

## 7. 한국어

두 원본의 실패 지점을 각각 고친다.

| 지점 | 원본의 문제 | Dyad |
|---|---|---|
| 형태소 분석 | mem0는 spaCy 영어 전용, 없으면 원문 통과 | **`Analyzer` SPI.** Nori(한국어) / Standard(영어) / Bigram(폴백)을 워크스페이스 설정으로 선택 |
| FTS 인덱스 | Honcho는 `to_tsvector('english', ...)` 하드코딩 | `content_analyzed` 컬럼을 **사전 분석**해 두고 `to_tsvector('simple', ...)` 사용 — 언어를 인덱스 밖으로 뺀다 |
| 엔티티 추출 | mem0는 대문자·따옴표 휴리스틱 | **LLM 추출 결과를 재사용** (§4.2). 언어 무관, 추가 비용 0 |
| BM25 신호 소실 | mem0는 분모까지 바뀜 | 고정 가중치라 순위 보존 (§5.1) |

`content_analyzed`를 저장 시점에 만들어 두는 게 핵심이다. Postgres의 언어 설정에 의존하지 않으므로
분석기를 교체해도 재색인만 하면 되고, 다국어 워크스페이스도 같은 인덱스를 쓴다.

---

## 8. 검증 설계 — 이 프로젝트의 리스크를 결정하는 부분

Honcho를 그대로 재구현할 때의 최대 문제는 **제대로 됐는지 증명할 방법이 없다**는 것이었다.
Dyad는 Tier 0·1을 순수 함수로 만들어 그 문제를 없앤다.

| 티어 | 검증 방법 |
|---|---|
| Tier 0 `context()` | 토큰 예산 분배가 결정적 → 픽스처 비교 |
| Tier 1 `recall()` | **`explain`의 6개 신호와 최종 score를 소수 6자리까지 비교.** 순위 뒤집힘 0건 |
| 중복 제거 | 해시·정규화·코사인 분기를 케이스별 픽스처로 |
| Deriver | LLM 응답을 스텁으로 고정 → 동일 결론 집합 생성 |
| Tier 2 `chat()` | **record/replay 하네스** — LLM 호출을 요청·응답 쌍으로 기록해 재생 |

record/replay 하네스는 **Deriver를 짜기 전에** 만들어야 한다. LLM이 비결정적이라
이 장치 없이는 회귀 테스트가 성립하지 않는다.

---

## 9. 로드맵

| Phase | 내용 | 기간 | 완료 기준 |
|---|---|---|---|
| **P0** | 스키마 + CRUD + JWT + 페이지네이션 + record/replay 하네스 | 4주 | 계층 CRUD가 돌고 하네스가 LLM 호출을 재생한다 |
| **P1** | 큐 + 클레임 + Deriver + 3단 중복 제거 + 이벤트 로그 + idle flush | 3주 | 스텁 LLM에서 동일 결론 집합이 생성된다 |
| **P2** | **Tier 1 `recall()`** — 융합 공식 + explain + Analyzer SPI | 3주 | **explain 6개 신호가 소수 6자리 일치** |
| **P3** | 엔티티 레이어 + 부스트 + 프로버넌스 조회 | 2주 | 결론 삭제 후 고아 엔티티가 남지 않는다 |
| **P4** | 2단 요약 + Tier 0 `context()` + 만료·감쇠 | 2주 | 토큰 예산 분배가 픽스처와 일치 |
| — | *여기까지 14주. 실사용 가능한 메모리 시스템* | | |
| **P5** | Tier 2 Dialectic — 툴 루프, 추론 레벨, SSE | 4주 | 열거·갱신 질의 정성 평가 |
| **P6** | Dreamer — 연역·귀납·모순, peer card | 4주 | 이벤트 로그로 모든 자율 변경 추적 가능 |

**P0~P4가 진짜 산출물이다.** P5·P6은 필요해질 때 얹으면 되고, 스키마가 이미 `level`·`source_ids`·
이벤트 로그를 갖고 있으므로 **추가 작업이지 재작업이 아니다.**

---

## 10. 기술 스택

| 관심사 | 선택 |
|---|---|
| 런타임 | Spring Boot 3.x (WebFlux) — API / Worker 프로파일 분리 |
| 영속성 | **jOOQ** 또는 Spring Data JDBC + Flyway. 복합 FK 때문에 JPA 비권장 |
| 벡터 | PostgreSQL 16 + pgvector, `com.pgvector:pgvector` |
| 형태소 | Lucene **Nori** (한국어) / StandardAnalyzer (영어) — `Analyzer` SPI 뒤 |
| BM25 | Lucene `BM25Similarity` 또는 Postgres `ts_rank_cd` |
| 토크나이저 | `com.knuddels:jtokkit` (`o200k_base`) |
| LLM | 공식 SDK(OpenAI/Anthropic) + 얇은 자체 추상화. 툴 루프 통제 때문 |
| 임베딩 | `text-embedding-3-small` 1536차원 (두 원본과 동일 → 데이터 이관 시 재임베딩 불필요) |
| ID | nanoid 21자 |
| SSE | `Flux<ServerSentEvent>` |
| 관측 | Micrometer + Prometheus |

---

## 11. 정직한 리스크

1. **가중치 6개를 튜닝해야 한다.** 초기값은 mem0에서 유래했지만 신호가 3개 늘었으므로 도메인 데이터로 재조정이 필요하다. 골든 픽스처는 "구현이 맞는가"를 증명하지 **"가중치가 좋은가"는 증명하지 못한다.** 별도의 랭킹 평가셋이 필요하다.

2. **Tier 1이 Dialectic을 대체하지 못한다.** 열거·집계, 모순 감지, 서술형 요약은 여전히 LLM이 필요하다. Tier 1의 목표는 대체가 아니라 **Tier 2 호출 빈도를 낮추는 것**이다. 이 기대치를 잘못 잡으면 P5를 안 만들고 끝내려다 실패한다.

3. **엔티티 추출 품질이 Deriver 프롬프트에 종속된다.** 별도 단계가 아니라 부산물이라 비용은 0이지만, 추출 프롬프트를 바꾸면 엔티티 레이어가 같이 흔들린다. 프롬프트 버전과 엔티티 재색인을 묶어 관리해야 한다.

4. **감쇠 파라미터는 도메인마다 다르다.** 반감기 180일은 임의값이다. 개인 비서와 코딩 에이전트의 적정값이 다르다. 워크스페이스 설정으로 노출하고 기본값은 보수적으로 잡는다.

5. **AGPL 경계.** 아키텍처 참조는 안전하지만, 구현 중에 Honcho 소스를 열어놓고 대조하는 순간 클린룸이 깨진다. 이 문서를 명세로 삼고 원본 저장소는 닫아둘 것.

---

## 12. 요약 — 세 시스템 대조

| 축 | mem0 | Honcho | **Dyad** |
|---|---|---|---|
| 스코프 | 평면 (`user/agent/run`) | peer 쌍 | **peer 쌍** |
| 저장소 | 3 (vector+entity+SQL) | 1 (Postgres) | **1 (Postgres)** |
| 사실 등급 | 없음 | 4단계 | **4단계 + confidence** |
| 추론 트리 | 없음 | `source_ids` | **`source_ids` + 엔티티 앵커** |
| 엔티티 | user 스코프 역색인 | 없음 | **workspace 노드 + 쌍 엣지** |
| 쓰기 | 동기 | 비동기 (최대 30분) | **비동기 + idle flush 3초 + `?wait`** |
| 중복 제거 | 해시 | 정규화 + 시맨틱 | **해시 + 정규화 + 시맨틱** |
| 값싼 조회 | 가산 융합 (분모 가변) | RRF (순위만) | **고정 가중 융합 6신호 + explain** |
| 비싼 조회 | 없음 | Dialectic | **Dialectic (Tier 1을 툴로 보유)** |
| 망각 | 수동 TTL | 없음 | **감쇠 + TTL + 아카이브 후보** |
| 감사 로그 | history | 없음 | **conclusion_events (자율 변경 포함)** |
| 한국어 | 신호 2개 소실 | FTS 무력 | **Analyzer SPI + 사전 분석 컬럼** |
| 검증 | 수치 재현 | 정성 | **Tier 0·1 수치 재현 + replay 하네스** |

**한 문장으로:** Honcho의 *되돌릴 수 없는 구조*를 뼈대로 삼고,
그 위에 mem0의 *결정적 검색 엔진*을 얹은 뒤, 두 시스템 모두 놓친 *망각과 감사*를 채워 넣은 것.
