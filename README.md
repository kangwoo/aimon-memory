**한국어** · [English](README.en.md)

# aimon-memory

[![ci](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](gradle/libs.versions.toml)

대화형 에이전트를 위한 메모리 시스템. 저장하는 모든 사실은 방향이 있는
**(observer, observed) 쌍**에 속한다 — `alice` 가 자기 자신을 기억한 내용과 `bot` 이 `alice` 를
기억한 내용은 서로 섞이지 않는 별개의 저장소다.

HTTP API 뒤에서 두 개의 프로세스로 돈다.
[`aimon-memory-client`](modules/aimon-memory-client) 가
[aimon-core](https://github.com/kangwoo/aimon-core) 의 `PeerMemory` 를 이 서비스 위에 구현하므로,
aimon-core 애플리케이션은 조립하는 `PeerMemory` 만 바꾸면 메모리 백엔드가 이쪽으로 바뀐다.

`aimon-memory-design.md` 와 `aimon-memory-build-plan.md` 를 보고 만들었다.

---

## 무엇을 하는가

메시지는 HTTP 로 들어오고 즉시 응답한다. 워커가 메시지를 묶어 오래 남을 사실을 뽑고, 이미 아는 것과
비교해 중복을 걷어낸 뒤, 관측하던 쌍 아래에 결과를 넣는다.

추출은 배치당 한 번이 아니라 **관측하는 쌍마다 한 번** 돈다. 프롬프트를 observer 쪽에서 쓰기 때문이다 —
`bob` 이 `alice` 에 대해 내릴 수 있는 결론과 `alice` 가 자신에 대해 내리는 결론은 다른 질문이다.
배치는 메시지당 비용을 없애지 observer 당 비용을 없애지 않는다. 서로를 관측하는 N 명이 참여한 세션은
배치당 N + N(N−1) 번 호출하고, `observe_others` 가 그 레버다
([ADR 0006](docs/adr/0006-fanout-cost.md)).

읽기는 3티어다.

| 티어 | 호출 | 모델 호출 | 통상 지연 | 결정적 |
|---|---|--:|--:|:-:|
| 0 | `context()` | 0 | ~50 ms | 예 |
| 1 | **`recall()`** | 0 | ~100 ms | 예 |
| 2 | `chat()` | 1–10 | 초 단위 | 아니오 |

**Tier 1 이 이것을 만든 이유다.** 메모리 시스템에 던지는 질문은 대부분 조회이고, 조회는 빠르고 싸고
매번 같은 답이어야 한다. 고정 가중치 아래 여섯 신호로 순위를 매긴다.

```
score = 0.50·sem + 0.22·kw + 0.13·ent + 0.08·reinf + 0.05·rec + 0.02·lvl
```

모든 응답은 그 점수를 만든 내역을 함께 싣고, 골든 픽스처가 그 전부를 소수 6자리까지 검사한다.

Tier 2 는 Tier 1 이 답할 수 없는 질문 — 열거, 모순, 서사가 필요한 것 — 을 위해 남아 있다. Tier 1 을
도구 중 하나로 쥐고 있고, 그게 반복 횟수를 낮춰 준다.

---

## 실행하기

### 요구 환경

- **JDK 21.** `gradle/libs.versions.toml` 의 `java` 에 고정돼 있다. Gradle wrapper 를 함께 넣어 뒀으므로
  `./gradlew` 는 JDK 말고 따로 설치할 것이 없다.
- **Docker.** `docker compose up` 과, 자기 데이터베이스를 직접 띄우는 Testcontainers 계층에 쓴다.

새로 클론해도 빌드되고 모든 관문을 통과한다. `gradle/libs.versions.toml` 의 `aimonCore` 는 Central 에
올라와 있는 릴리스 `0.2.4` 라서, `git clone` 에서 `checkAll` 까지 가는 길 어디에서도 한 기계에만 있는
아티팩트에 손을 뻗지 않는다. 예외가 하나 있는데, 빌드를 깨뜨리는 대신 스스로 건너뛴다.
`:aimon-memory-client:contractTest` 는 `at.aimon.core:aimon-memory-testkit` 을 상속하는데, 이
아티팩트는 aimon-core 0.3.0 이 나오기 전까지 어느 원격 저장소에도 없다. 그래서 `aimonTestkit` 으로
따로 고정해 `mavenLocal()` 로 풀고, 건너뛸 때는 이유를 이름으로 짚어 준다. 이 계층을 돌리려면 먼저
아티팩트를 만들어야 한다.

```sh
# aimon-core 체크아웃에서
./gradlew publishToMavenLocal -PVERSION_NAME=0.3.0-SNAPSHOT
```

좌표를 왜 둘로 갈랐는지, 그리고 그 스위트를 처음 돌렸을 때 무엇이 나왔는지는
[ADR 0007](docs/adr/0007-aimon-core-boundary.md) 에 있다.

```sh
docker compose up -d                 # postgres 16 + pgvector
./gradlew checkAll                   # 포맷·스타일·BOM, 그리고 데몬이 필요 없는 테스트
./gradlew integrationTest            # 자기 데이터베이스를 띄우는 Testcontainers 계층

export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
./gradlew :aimon-memory-api:bootRun          # HTTP, 8080 포트
./gradlew :aimon-memory-worker:bootRun
```

`checkAll` 은 빠른 관문이고, 이 시스템의 동작은 사실상 전부 `integrationTest` 에서 증명된다 — 부분
유니크 인덱스, pgvector 거리, Flyway 마이그레이션 사슬은 목(mock)이 대신할 수 있는 것이 아니다. 둘 다
CI 의 관문이다. 포맷 실수 하나가 데이터베이스 뒤에서 기다리지 않도록 나눠 뒀다.

`AIMON_MEMORY_JWT_SECRET` 은 필수이고 기본값이 없다. `application.yml` 에 개발용 기본값을 두면 서명
키를 저장소에 공개하는 셈이다. 환경변수를 빠뜨린 배포가 멀쩡히 뜬 다음 그 키로 운영 토큰에 서명하고,
소스를 읽을 수 있는 누구에게나 admin 토큰을 넘겨주게 된다. 그러느니 기동을 실패시킨다.

나머지는 자격 증명 없이도 뜬다. 임베더는 로컬 해싱 구현으로 물러나고, 모델 제공자는 completion 을
요청받는 순간 분명한 메시지로 실패하는 쪽으로 물러난다 — 모든 경로를 돌려 보기에는 충분하고, 그
이상으로는 쓸 수 없다고 분명히 표시돼 있다.

실제 제공자를 붙이려면,

```sh
export OPENAI_API_KEY=...
export AIMON_MEMORY_LLM_PROVIDER=openai
export AIMON_MEMORY_EMBED_PROVIDER=openai
export AIMON_MEMORY_LLM_FALLBACK=anthropic ANTHROPIC_API_KEY=...   # 선택
```

### 스모크 테스트

`scripts/smoke.sh` 는 떠 있는 두 프로세스를 HTTP 로 훑는다. 헬스, workspace, 수집, 결론 주입, 신호
내역까지 갖춘 한국어 recall, 엔티티 프로버넌스(provenance), 감사 추적. 단위 테스트가 못 하는 것을
증명한다 — 두 jar 가 실제로 부팅되는지, Flyway 가 빈 데이터베이스에 모든 마이그레이션을 적용하는지,
Nori 분석이 질의 경로까지 닿는지.

```sh
AIMON_MEMORY_JWT_SECRET=... ./scripts/smoke.sh
```

### 첫 요청

```sh
TOKEN=$(curl -s localhost:8080/v1/tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scope":"workspace","workspace":"demo"}' | jq -r .token)

curl -s localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"messages":[{"peer":"alice","content":"I work at a bank in Gangnam, Seoul."}]}'

curl -s localhost:8080/v1/workspaces/demo/recall \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"where does alice work","observer":"alice","observed":"alice"}'
```

저 workspace 토큰은 자기보다 넓지만 않다면 다시 토큰을 발급할 수 있다 — 대화 하나짜리 session 토큰을
만들어 브라우저에 건네되, 그것을 발급하는 서비스 근처에 admin 키를 두지 않는 식이다. 넓히는 것은
거부한다. 다른 workspace, 더 넓은 scope, 발급자가 대변하지 않는 peer 는 안 된다.

---

## 모듈

의존성은 아래로만 향하고, `ModuleDependencyTest` 가 그것을 강제한다.

```
aimon-memory-core      no dependencies. Domain types, the six SPIs, key encoding.
aimon-memory-testkit   core. Golden fixtures, stubs, the Testcontainers base.

aimon-memory-text      core. Nori / Standard / bigram analyzers, normalisation, BM25, jtokkit.
aimon-memory-embed     core, text. Batching, truncation, retry, order preservation.
aimon-memory-llm       core. Provider backends, structured output, tool loop, record/replay.
aimon-memory-store     core, text. Flyway, repositories, pgvector, the filter compiler.

aimon-memory-recall    core, store, text, embed. Six signals, fusion, explain, provenance.
aimon-memory-engine    + llm, recall. Deriver, summariser, context, dialectic, dreamer.

aimon-memory-worker    engine, store.            [runnable]
aimon-memory-api       recall, engine, store.    [runnable]

aimon-memory-client    aimon-core only.          [the adapter, Java 17]
aimon-memory-bom       nothing. A java-platform pinning the published modules.
```

프로세스는 둘, 코드베이스는 하나다. 둘은 다르게 확장되고 다르게 실패한다. API 는 지연에 묶여 있고 마음껏
재시작해도 되지만, 워커는 재시작할 때 풀어 줘야 할 큐 클레임을 쥐고 있다.

`aimon-memory-engine` 이 티어 그 자체다 — deriver, dialectic, dreamer, fan-out, 수집. 모듈 이름도
패키지 이름도 제품 이름을 되풀이하지 않도록 `memory` 가 아니라 `engine` 이라고 지었다.

---

## aimon-core 에서 쓰기

`aimon-memory-client` 가 이음매다. aimon-core 는 `at.aimon.core.memory.PeerMemory` 에서 메모리
백엔드를 갈아 끼운다 — 서비스 높이에 놓인 다섯 티어이고, 바로 이 경우를 내다본 주석이 붙어 있다.
"the store-backed default and a remote memory service both have a name for" 라고.

```java
PeerMemory memory = new RemotePeerMemory(RemoteMemoryOptions.builder()
        .baseUri("https://memory.internal:8080")
        .token(tokens::current)          // called per request; tokens expire
        .agentPeer("assistant")          // who ASSISTANT-role messages are stored as
        .build());
```

| aimon-core 티어 | 엔드포인트 |
| --- | --- |
| `SNAPSHOT` | `GET /v1/workspaces/{ws}/conclusions` |
| `SEARCH` | `POST /v1/workspaces/{ws}/recall` |
| `CHAT` | `POST /v1/workspaces/{ws}/chat` |
| `OBSERVE` | `POST /v1/workspaces/{ws}/conclusions` |
| `INGEST` | `POST /v1/workspaces/{ws}/sessions/{session}/messages` |

쌍은 번역 없이 그대로 건너온다. aimon-core 의 subject 가 observed 이고 observer 가 observer 이니,
이 시스템이 모든 행을 키잉하는 그 방향 있는 쌍과 같다. observer 를 지정하지 않은 질의는 subject 자신의
self-pair 를 가리킨다.

능력 신호 세 개는 false 이고, 셋 다 빠뜨린 것이 아니라 실제로 다른 점이다 — 결론은 그것을 만든 세션보다
오래 살기 때문에 recall 은 세션으로 좁히지 않는다. 주입된 관측의 confidence 는 받아 적는 것이 아니라
등급과 강화 횟수에서 나온다. 수집은 도출하지 않고 큐에 넣으므로 접수증에 `derived` 가 실리는 일이 없다.

빌드는 다섯 티어를 담은 첫 릴리스인 `at.aimon.core:aimon-core:0.2.4` 를 대상으로 하고, 그 좌표 하나면
된다 — 형제 체크아웃도, 컴포짓 빌드도 필요 없다. 그것을 지키는 것이
`:aimon-memory-client:verifyCoreIsReleased` 다. aimon-core 가 릴리스된 아티팩트가 아니라 프로젝트로
풀렸을 때, 스냅샷으로 풀렸을 때, 풀린 jar 안에 `PeerMemory` 가 실제로 없을 때 publish 를 거부한다.

그 좌표 너머로 손을 뻗는 것은 계약 스위트 하나뿐이다. `at.aimon.core:aimon-memory-testkit` 은
aimon-core 0.3.0 에서 처음 나오므로 `aimonTestkit` 으로 따로 고정했고, 그래서
`:aimon-memory-client:contractTest` 는 아티팩트가 없는 곳에서 스스로 건너뛰는 별도 소스셋이다 —
[실행하기](#실행하기) 참고.

---

## 설계 노트

코드만 봐서는 드러나지 않는 결정 여섯 개.

**메모리는 쌍에 속한다. 1일차에 정했다.** `(workspace, observer, observed)` 는 모든 결론에 걸린 복합
외래키다. 나중에 넣는 것은 마이그레이션이 아니라 재작성이라서, 아직 아무도 필요로 하기 전에 넣었다.

**순위의 분모는 상수다.** 가중치 합은 1.00 이고, 어떤 신호가 발화하지 않아도 그대로다. 이 공식이 나온
시스템은 응답한 스토어 수로 다시 스케일링해서, 같은 결론이 설정에 따라 다른 점수를 받았다. 여기서 빠진
신호는 0 을 낸다. 점수는 정직하게 떨어지고 후보들 사이의 순서는 건드리지 않는다.

**임계값은 융합 점수를 자른다.** 시맨틱 유사도만 놓고 자르면, 정확한 키워드 일치가 막 건져 올리려던
바로 그 행들을 버리게 된다.

**망각은 생성 시점이 아니라 마지막 강화 시점부터 잰다.** 계속 언급되는 사실은 자기 시계를 계속
되돌리며 늙지 않고, 한 번 언급되고 만 것은 알아서 내려간다. `times_derived` 는 중복 제거가 이미 세고
있던 값이다 — 한쪽 시스템은 순위에 쓰지 않으면서 세고, 다른 쪽은 이 값 없이 순위를 매긴다.

**언어는 인덱스 설정이 아니라 컬럼이다.** `content_analyzed` 는 쓰기 시점에 workspace 의 분석기가
만들고 `simple` 사전으로 색인한다. 인덱스 하나가 한국어·영어·bigram 폴백 workspace 를 모두 받고,
분석기를 바꾸는 일은 마이그레이션이 아니라 재색인이 된다.

**결론을 바꾸는 모든 것은 이벤트를 쓴다.** 장부 정리가 아니다. Dreamer 는 아무도 보지 않는 사이 메모리를
고치고, 로그가 없으면 사람이 한 번도 말한 적 없는 믿음에 대해 "이건 어디서 왔나"에 답할 길이 없다.

---

## 테스트

```
401  tests, all green
165  of them need no database (`checkAll`)
236  of them do (`integrationTest`)
```

| 계층 | 방법 | 관문 |
|---|---|---|
| 순위 | 골든 픽스처 | 소수 6자리, 순위 뒤집힘 0건 |
| 중복 제거 | Testcontainers | 분기마다, 경계마다 테스트 하나씩 |
| 정규화 | Java 와 SQL 을 나란히 | 문자 단위로 일치 |
| LLM 경로 | record/replay | CI 는 replay 전용, 미스는 실패 |
| 인가 | 라우트 allowlist | 정책 항목이 없는 라우트는 빌드를 깬다 |
| 아키텍처 | ArchUnit | 의존성은 한 방향 |
| 인덱스 사용 | seqscan 끈 `EXPLAIN` | 모든 인덱스가 실제 질의로 닿는다 |
| 순위 품질 | 베이스라인 대비 nDCG / MRR | 질의별로도 총합으로도 퇴행 금지. 베이스라인은 측정한 코퍼스를 함께 기록한다 |
| 부하 | 동시 읽기·쓰기 | 오류 0건, 경합 아래에서도 빈틈 없는 시퀀스 (CI 는 작은 프로파일로 돈다) |
| 설정 | 쓰기 경계에서 검증 | 모르는 키나 못 쓸 값은 422 이지 조용한 폴백이 아니다 |
| 제공자 와이어 포맷 | 임시 포트에 실제 서버 | 요청 본문을 검사한다. 그것을 만든 객체가 아니라 |
| aimon-core 어댑터 | 임시 포트에 실제 서버 | 쌍의 방향, 다섯 티어의 본문, 정직한 능력 신호 셋 |

이 스위트에서 알아 둘 것 두 가지.

`RoutePolicyCoverageTest` 는 살아 있는 핸들러 매핑을 훑어서 `RoutePolicy` 에 없는 라우트가 있으면
실패한다. 누가 호출해도 되는지 정하지 않고 엔드포인트를 추가하면 프레임워크 기본값을 달고 나가는 대신
빌드가 깨진다.

`NormalisationParityTest` 는 `Normalizer.normalize` 를 그 SQL 쌍둥이와 비교한다. 한 번이라도 말썽을
일으킨 모든 입력에 대해서다. 개발 중에 진짜 어긋남을 하나 찾아냈다. Postgres `btrim` 은 공백만 벗기는데
Java 의 `strip()` 은 모든 whitespace 를 벗겨서, 탭이 든 내용이 중복 제거 2단계를 조용히 건너뛰고
있었다. 지금은 양쪽이 하나의 명시적 규칙에 묶여 있다.

골든 픽스처는 공식이 명세대로 구현됐다는 것을 증명한다. 가중치가 좋은지에 대해서는 아무 말도 하지 않는다
— 그건 라벨링된 평가 세트가 필요하고, 별도의 관문이다.

---

## 평가

관문이 셋이고, 서로 다른 이유로 실패하기 때문에 일부러 갈라 뒀다.

**골든 픽스처** (`test-fixtures/golden/`) 는 모든 신호와 융합 점수를 소수 6자리까지 못 박는다. 공식이
명세대로 구현됐음을 증명한다. 나쁜 공식의 올바른 구현과 좋은 공식의 올바른 구현은 구별하지 못한다.
잘못된 가중치도 일관된 답을 내고, 픽스처는 그것을 충실히 기록하기 때문이다.

**라벨링된 순위 세트** (`test-fixtures/eval/ranking.json`) — 한국어 결론 40개, 질의 50개, 등급이 매겨진
판정 103개 — 를 nDCG@5/@10, MRR, recall@10 으로 채점해 커밋된 베이스라인과 견준다. 관문은 한쪽으로만
열려 있다. 나아지면 통과, 퇴행하면 실패이고, 총합뿐 아니라 질의별로도 그렇다.

```
mean nDCG@5 0.671   nDCG@10 0.700   MRR 0.795   recall@10 0.655
```

이 숫자는 품질 주장이 아니라 퇴행 베이스라인으로 읽어야 한다. 제공자 자격 증명이 없으면 임베더가
어휘적으로 동작하므로 `sem` 이 두 번째 키워드 신호처럼 굴고, 그래서 가장 약한 질의가 개념적인
것들이다(*"앨리스의 여행 계획"* 은 0.000 인데, "여행 계획"과 "오사카행 항공권을 예약했다"를 잇는 어휘적
연결이 없기 때문이다). 반대로 가장 강한 질의는 용어를 그대로 부르는 것들이다. 이 두 숫자의 간격이 진짜
임베더가 사 오는 것의 공정한 추정치다. 50개 질의 중 12개가 0.35 아래인데 그 전부가 개념적 질의이고,
그것이 이 간격의 모양이다.

깨뜨려서 확인했다. 가중치를 최신성 쪽으로 옮기면 평균이 떨어지고 관문이 실패한다.

**정성 dialectic 세트** (`test-fixtures/eval/dialectic.json`) — 열거·대체·모순·판단 보류·프로버넌스에
걸친 질의 30개와 가중 루브릭. 사람이 채점한다. 답이 근거를 딛고 있는지 그럴듯하기만 한지는 판단의
영역이고, 스크립트로 짠 모델은 자기 숙제를 자기가 채점하는 셈이 되기 때문이다.
`./scripts/dialectic-sheet.sh` 가 채점표를 찍어 주고, 세트 자체가 썩지 않도록 테스트가 지킨다.

## 아직 안 된 것

발견하게 두는 대신 그냥 적는다.

- **순위 가중치는 실제 의도를 놓고 튜닝되지 않았다.** 위의 관문은 퇴행을 잡을 뿐, 가중치가 맞다고는 못
  한다. 판정이 합성 임베더를 기준으로 채점되기 때문이다. 실제 트래픽이 필요하고, 가중치가 설정값인 이유가
  바로 이것이다.
- **평가 세트는 계획이 요구한 100–200개가 아니라 50개 질의다.** 일부러 그랬다. 임베더 자리에 어휘적
  대역이 서 있는 동안에는, 손으로 쓴 판정을 늘려 봐야 퇴행 관문만 날카로워지고 가중치에 대한 확신은
  늘지 않는다. 나머지 간격은 집필이 아니라 트래픽으로 메워진다.
- **커밋된 LLM 픽스처가 없다.** replay 장치는 다단계 툴 루프까지 포함해 끝에서 끝까지 증명됐지만, 스위트에
  기록된 호출은 전부 스크립트로 짠 백엔드에서 나온 것이다. 자격 증명이 생기면
  `./scripts/record-fixtures.sh` 가 실제 제공자를 상대로 기록한다.
- **프롬프트는 채점되지 않았다.** 세트와 루브릭은 써 뒀지만, 실제 모델에 돌려 채점표를 채운 사람이 아직
  없다.
- **부하 수치는 노트북에서 나왔다.** 하네스도 숫자도 진짜지만(런북 참고), 그것이 묘사하는 것은 개발자
  기계 위의 컨테이너이지 운영 하드웨어가 아니다. CI 는 이 프로파일을 작은 크기로 돌려 오류 0건과 빈틈
  없는 시퀀스만 검사하고 타이밍은 무시한다.

## 문서

정본은 한국어다. 모든 문서는 같은 경로에 `.en.md` 접미사를 붙인 영어판을 함께 둔다 — 이 문서의 영어판은
[`README.en.md`](README.en.md) 다. 명세 두 건만 예외로 한국어만 있고, 왜 그런지와 각 절이 어디 있는지는
[`docs/spec/README.md`](docs/spec/README.md) 에 적어 뒀다.

- [`docs/spec/aimon-memory-design.md`](docs/spec/aimon-memory-design.md) — 명세
- [`docs/spec/aimon-memory-build-plan.md`](docs/spec/aimon-memory-build-plan.md) — 이것을 만든 계획
- [`docs/adr/`](docs/adr/README.md) — 위 두 문서에서 어디를 어떻게 벗어났는지, 왜인지, 근거와 함께.
  aimon-core 쪽에서 왔다면 [ADR 0007](docs/adr/0007-aimon-core-boundary.md) 부터 읽으면 된다. 두
  저장소 사이의 경계를 긋는 문서다
- [`docs/runbook.md`](docs/runbook.md) — 배포, 튜닝, 뭔가 잘못됐을 때 볼 것
- [`docs/dashboards/`](docs/dashboards) — Grafana 대시보드 `aimon-memory-overview.json`
- [`test-fixtures/README.md`](test-fixtures/README.md) — 픽스처 코퍼스 두 벌과 각각이 증명하는 것

## 라이선스와 기여

Apache-2.0 — [LICENSE](LICENSE). 이것을 가져다 쓰는 aimon-core 와 같은 라이선스이고,
[ADR 0005](docs/adr/0005-agpl-boundary.md) 의 클린룸 경계가 지켜 내려는 것이 바로 이 라이선스다.

- [CONTRIBUTING.md](CONTRIBUTING.md) — 변경을 제안하는 방법과 그것이 통과해야 할 관문
- [SECURITY.md](SECURITY.md) — 취약점 신고 방법
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — 참여하는 사람에게 기대하는 것
- [CHANGELOG.md](CHANGELOG.md) — 릴리스별 변경 내역
