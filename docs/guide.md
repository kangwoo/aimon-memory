**한국어** · [English](guide.en.md)

# 사용자 가이드

이 문서는 aimon-memory 를 **실제로 호출하는 방법**을 처음부터 끝까지 따라간다. 개념이 왜 그렇게
생겼는지는 [개념 문서](concepts.md)에, 라우트와 스키마의 정확한 형태는
[`openapi.json`](openapi.json)에, 배포와 장애 대응은 [런북](runbook.md)에 있다.

예제는 전부 `curl` 이다. 언어를 가리지 않고 그대로 옮길 수 있고, 무엇이 오가는지 숨기지 않기 때문이다.

---

## 목차

1. [10분 만에 첫 기억 만들기](#1-10분-만에-첫-기억-만들기)
2. [토큰 — 가장 먼저 막히는 곳](#2-토큰--가장-먼저-막히는-곳)
3. [workspace, peer, session](#3-workspace-peer-session)
4. [기억을 넣는 두 가지 방법](#4-기억을-넣는-두-가지-방법)
5. [누가 누구를 관측하는가](#5-누가-누구를-관측하는가)
6. [읽기 — 어느 티어를 쓸 것인가](#6-읽기--어느-티어를-쓸-것인가)
7. [explain 읽는 법](#7-explain-읽는-법)
8. [필터](#8-필터)
9. [프로버넌스와 감사 추적](#9-프로버넌스와-감사-추적)
10. [workspace 튜닝](#10-workspace-튜닝)
11. [한국어 쓰기](#11-한국어-쓰기)
12. [dream 과 peer card](#12-dream-과-peer-card)
13. [에러 사전](#13-에러-사전)
14. [자주 하는 실수](#14-자주-하는-실수)
15. [aimon-core 에서 쓰기](#15-aimon-core-에서-쓰기)

---

## 1. 10분 만에 첫 기억 만들기

### 준비물

- **JDK 21** — `gradle/libs.versions.toml` 에 고정돼 있다. Gradle wrapper 가 함께 들어 있으므로
  `./gradlew` 는 JDK 말고 설치할 것이 없다.
- **Docker** — Postgres 16 + pgvector 를 띄우는 데 쓴다.

제공자 자격 증명은 **없어도 된다.** 임베더는 로컬 해싱 구현으로, 모델 제공자는 completion 을
요청받는 순간 분명한 메시지로 실패하는 쪽으로 물러난다. 모든 경로를 돌려 보기에는 충분하다.

### 띄우기

```sh
docker compose up -d                          # postgres 16 + pgvector

export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
./gradlew :aimon-memory-api:bootRun &         # HTTP, 8080
./gradlew :aimon-memory-worker:bootRun &      # 워커
```

`AIMON_MEMORY_JWT_SECRET` 은 **필수이고 기본값이 없다.** 없으면 기동이 실패한다. 32바이트 미만이어도
실패한다. 왜 그렇게까지 하는지는 [토큰](#2-토큰--가장-먼저-막히는-곳) 절에 있다.

두 프로세스가 다 떴는지는 관리 포트로 확인한다. 서비스 포트가 아니다.

```sh
curl -s localhost:9090/actuator/health   # API
curl -s localhost:9091/actuator/health   # 워커
```

### 첫 번째 토큰

`/v1/tokens` 는 **기존 토큰을 좁힐 뿐 무에서 만들지 못한다.** 그래서 첫 토큰 하나는 직접 서명해야
한다. 아래 스크립트는 `scripts/smoke.sh` 에 들어 있는 것과 같은 것이다.

```sh
export ADMIN_TOKEN=$(python3 - "$AIMON_MEMORY_JWT_SECRET" <<'PY'
import base64, hmac, hashlib, json, sys, time
secret = sys.argv[1].encode()
b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
now = int(time.time())
header  = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64(json.dumps({"iss": "aimon.memory", "iat": now, "exp": now + 3600,
                          "scope": "admin"}, separators=(",", ":")).encode())
sig = b64(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}", end="")
PY
)
```

### 기억을 넣고 다시 꺼내기

```sh
auth=(-H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json')

# 1. 메시지를 넣는다. workspace, session, peer 는 없으면 알아서 생긴다.
#    ?wait=derive 는 도출이 끝날 때까지 기다린다 (최대 30초).
curl -s -X POST 'localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive' "${auth[@]}" \
  -d '{"messages":[{"peer":"alice","content":"저는 서울 강남에 있는 은행에서 일해요."}]}'

# 2. 앨리스가 자기 자신에 대해 아는 것을 묻는다.
curl -s -X POST localhost:8080/v1/workspaces/demo/recall "${auth[@]}" \
  -d '{"query":"앨리스는 어디서 일하나","observer":"alice","observed":"alice","explain":true}'
```

두 번째 호출이 결론 하나와 그것을 만든 여섯 신호의 내역을 돌려주면 성공이다.

> **모델 제공자를 안 붙였는데 결론이 안 나온다면** — 정상이다. Deriver 는 모델 호출이라서 제공자
> 없이는 아무것도 뽑지 못한다. 그래도 recall 을 시험해 보려면 [결론을 직접
> 주입](#방법-b--결론을-직접-주입한다)하면 된다. 그쪽은 모델을 쓰지 않는다.

### 전체를 한 번에 훑고 싶다면

```sh
AIMON_MEMORY_JWT_SECRET=... ./scripts/smoke.sh
```

헬스, workspace, 수집, 결론 주입, 신호 내역까지 갖춘 한국어 recall, 엔티티 프로버넌스, 감사 추적을
차례로 친다.

---

## 2. 토큰 — 가장 먼저 막히는 곳

### 네 개의 중첩된 스코프

| 스코프 | 무엇을 대변하는가 | 본문에 반드시 있어야 하는 것 |
|---|---|---|
| `admin` | 전부 | — |
| `workspace` | workspace 하나 | `workspace` |
| `peer` | 한 workspace 안의 peer 하나 | `workspace`, `peer` |
| `session` | 한 workspace 안의 대화 하나 | `workspace`, `session` |

**넓은 것이 좁은 것을 만족한다.** `peer` 토큰을 요구하는 라우트는 `workspace` 토큰과 `admin` 토큰도
받는다. 반대는 안 된다.

### 좁히기만 되고 넓히기는 안 된다

`POST /v1/tokens` 는 **자기보다 넓은 토큰을 만들지 못한다.** 세 가지가 막혀 있다.

- 자기보다 넓은 스코프
- 다른 workspace
- 발급자가 peer 토큰이면, 자기가 아닌 peer 를 지목하는 토큰

이 나눔이 무엇을 사 오는가. workspace 토큰을 쥔 서비스가 대화 하나짜리 session 토큰을 만들어
브라우저에 건넬 수 있다. admin 키를 그 근처에 두지 않고서. 그리고 브라우저는 그것을 다시 넓힐 수 없다.

```sh
# workspace 토큰으로, 세션 하나짜리 토큰을 만들어 클라이언트에 준다
curl -s -X POST localhost:8080/v1/tokens \
  -H "Authorization: Bearer $WS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scope":"session","workspace":"demo","session":"s1","lifetimeSeconds":3600}'
```

### `allowMemberRead`

session 토큰은 기본적으로 **쓰기만** 할 수 있다. 메시지는 넣을 수 있어도 그 세션의 기록을 읽지는
못한다. 좁은 토큰은 브라우저에 건네는 그것이고, 기본값이 읽기를 허용하면 그런 토큰 하나하나가 세션
기록 내보내기가 되기 때문이다.

읽기가 필요하면 발급할 때 명시한다.

```json
{"scope":"session","workspace":"demo","session":"s1","allowMemberRead":true}
```

이걸 켜면 그 session 토큰이 `GET .../sessions/{s}`, `.../context`, `.../messages`, `.../peers`,
`POST .../messages/search` 다섯 개를 추가로 읽을 수 있다.

### 수명

- 기본 **12시간** (`aimon.memory.jwt.lifetime`).
- 요청당 `lifetimeSeconds` 로 조정.
- 상한 **30일**, 예외 없음. **폐기 목록이 없어서 만료가 유출된 토큰을 끝내는 유일한 수단**이다.

`aimon.memory.jwt.lifetime` 을 30일보다 크게 잡으면 기동이 실패한다. 그냥 떴다면 `lifetimeSeconds` 를
생략한 모든 토큰 발급이 400 으로 죽었을 것이고, 그 오류는 엉뚱한 쪽을 가리켰을 것이다.

### 누가 누구를 사칭할 수 있는가

메시지를 넣을 때 화자는 **본문**에 있다. 경로가 아니다. 그래서 별도 검사가 하나 더 있다.

- **peer 토큰**은 자기 이름으로만 메시지를 쓸 수 있다.
- **session 토큰**은 그 세션의 **누구 이름으로든** 쓸 수 있다. 그게 이 토큰의 용도다 — 대화를
  받아 적는 쪽은 참여자 전원을 받아 적어야 한다.

peer 토큰이 남의 이름을 서명할 수 있었다면, 그 메시지가 모든 관측자의 기억으로 퍼져 나가 그 사람에
대한 결론이 되고, 감사 추적에는 누가 실제로 호출했는지 아무것도 남지 않는다.

---

## 3. workspace, peer, session

### 만들지 않아도 된다

셋 다 처음 쓰일 때 자동으로 생긴다. `POST .../sessions/s1/messages` 한 번이면 workspace, session,
peer, 세션 멤버십까지 다 만들어진다.

명시적으로 만드는 라우트는 **설정이나 메타데이터를 함께 주고 싶을 때** 쓴다.

```sh
# workspace 를 만들면서 한국어로 설정한다
curl -s -X POST localhost:8080/v1/workspaces/demo "${auth[@]}" \
  -d '{"configuration":{"language":"ko"},"metadata":{"tenant":"acme"}}'
```

### 목록과 조회

```sh
curl -s -G localhost:8080/v1/workspaces "${auth[@]}" --data-urlencode 'page=0' --data-urlencode 'size=50'
curl -s localhost:8080/v1/workspaces/demo/peers "${auth[@]}"
curl -s localhost:8080/v1/workspaces/demo/sessions "${auth[@]}"
```

`page` 는 0부터(상한 10000), `size` 는 기본 50 · 상한 200. 상한을 넘겨도 **거부하지 않고 잘라 준다** —
더 달라는 요청은 실패시킬 만한 착오가 아니고, 더 남았는지는 페이지네이션이 이미 말해 주기 때문이다.
(값을 못 지키는 필터는 다른 얘기이고, 그건 여전히 422 다.)

### 세션 멤버십

누가 이 대화에 있는지, 그리고 각자가 무엇을 관측하는지.

```sh
# 관측 스위치를 지정해서 참여시킨다
curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/s1/peers "${auth[@]}" \
  -d '{"peers":[{"peer":"alice","observeMe":true,"observeOthers":false},
                {"peer":"bot","observeMe":false,"observeOthers":true}]}'

curl -s localhost:8080/v1/workspaces/demo/sessions/s1/peers "${auth[@]}"       # 현재 명단
curl -s -X DELETE localhost:8080/v1/workspaces/demo/sessions/s1/peers/alice "${auth[@]}"
```

`PUT` 은 명단을 통째로 갈아 끼우고, `POST` 는 더한다.

멤버십에는 창이 있다. 나갔다가 다시 들어온 peer 는 처음에 들은 것을 유지하면서 그 사이의 공백은
얻지 않는다. 메시지 시점에 없었던 사람의 기억에는 그 메시지가 들어가지 않는다.

---

## 4. 기억을 넣는 두 가지 방법

### 방법 A — 메시지를 넣고 도출하게 둔다

일반적인 경로다. 대화가 흘러가는 대로 넣는다.

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/s1/messages "${auth[@]}" \
  -d '{"messages":[
        {"peer":"alice","content":"다음 주에 오사카 갑니다.","metadata":{"turn":12}},
        {"peer":"bot","content":"항공권은 예매하셨나요?"}
      ]}'
```

- 요청당 **최대 100건**.
- `metadata` 는 자유 형식 JSON. 시스템이 해석하지 않고 그대로 보관한다.
- **즉시 반환한다.** 모델 호출은 워커가 나중에 한다.

**언제 결론이 생기는가.** 배치 게이트 셋 중 하나가 참이 될 때다. 기본값으로는 대화가 3초 쉬면
나간다(`batch.idle_flush_seconds`). 부하가 걸리면 토큰이 먼저 찬다(512).

**방금 넣은 것을 바로 읽어야 한다면** `?wait=derive` 를 붙인다.

```sh
curl -s -X POST 'localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive' "${auth[@]}" \
  -d '{"messages":[{"peer":"alice","content":"다음 주에 오사카 갑니다."}]}'
```

방금 큐에 넣은 work unit 이 비워질 때까지 **최대 30초** 기다린다. 30초를 넘겨도 **오류가 아니다** —
메시지는 저장됐고 작업은 큐에 있다. 이번 응답에서 결론을 못 볼 뿐이다.

요청당 옵션인 것이 중요하다. 전역 스위치였다면 read-your-writes 가 필요한 소수를 위해 모두가 배칭의
이득을 내놓아야 했을 것이다.

### 방법 B — 결론을 직접 주입한다

이미 아는 사실을 넣는다. **모델을 쓰지 않는다.** 마이그레이션, 시드 데이터, 사용자가 프로필에 직접
입력한 것에 쓴다.

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/conclusions "${auth[@]}" \
  -d '{"observer":"alice","observed":"alice","session":"s1",
       "content":"앨리스는 서울 강남의 은행에서 일한다",
       "entities":["서울","강남"],
       "expiresAt":"2027-01-01T00:00:00Z"}'
```

| 필드 | 필수 | 비고 |
|---|:-:|---|
| `observer`, `observed` | 예 | 이 기억이 누구 것이고 누구에 대한 것인지 |
| `content` | 예 | 사실 한 줄 |
| `session` | 아니오 | 어디서 나온 것인지. 없어도 된다 |
| `entities` | 아니오 | 명시하면 그대로 건다. 없으면 추출을 시도한다 |
| `expiresAt` | 아니오 | 이 시각이 지나면 리컨실러가 만료시킨다 |

주입된 결론도 중복 제거를 지난다. 이미 같은 말을 하는 행이 있으면 새로 만들지 않고 강화된다.

`confidence` 를 받아 적지 않는다는 점을 알아 둘 것. 등급과 강화 횟수에서 나온다.

### 지우기

```sh
curl -s -X DELETE localhost:8080/v1/workspaces/demo/conclusions/{id} "${auth[@]}"
```

soft-delete 다. `delete` 이벤트가 남고, 엔티티 간선을 정리하고 고아가 된 노드를 거두는 일은 큐로
넘어간다 — 호출자는 행 하나를 지워 달라고 한 것이지 테이블 스캔을 기다리겠다고 한 것이 아니다.

---

## 5. 누가 누구를 관측하는가

이 절이 **비용을 정한다.** 열기 전에 읽어 둘 것.

### 두 개의 스위치

- `observe_me` — 말한 사람이 자기 자신을 기억한다. 쌍 `(p, p)`.
- `observe_others` — 듣는 사람이 말한 사람을 기억한다. 쌍 `(listener, speaker)`.

둘 다 기본값 `true`. **workspace → session → 메시지 순으로 좁은 쪽이 이긴다.**

### 왜 비용인가

관측하는 쌍 하나하나가 **자기 work unit, 자기 배치, 자기 추출 호출**을 갖는다. fan-out 은 저장만이
아니라 모델 호출에 걸린다.

서로를 관측하는 N 명의 세션은 배치당 **N + N(N−1)** 회 호출한다.

| 인원 | 배치당 호출 |
|--:|--:|
| 2 | 4 |
| 3 | 9 |
| 5 | 25 |
| 10 | 100 |

10명짜리 방을 기본값으로 열면 배치마다 100번을 부른다. 그럴 생각이 아니었다면 레버는 하나다.

```sh
# 봇만 남을 관측한다. 사람들은 자기 자신만 기억한다.
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"observe_others":false}}'

curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/room/peers "${auth[@]}" \
  -d '{"peers":[{"peer":"bot","observeOthers":true}]}'
```

이러면 N + N 이 된다. 왜 이 비용을 명세와 달리 감수했는지는
[ADR 0006](adr/0006-fanout-cost.md) 에 있다.

---

## 6. 읽기 — 어느 티어를 쓸 것인가

| 이런 질문이라면 | 이걸 쓴다 |
|---|---|
| "다음 턴에 넣을 대화 맥락을 줘" | **Tier 0** `GET .../context` |
| "앨리스가 어디서 일하지?" | **Tier 1** `POST .../recall` |
| "앨리스에 대해 아는 걸 전부 나열해 줘" | **Tier 2** `POST .../chat` |
| "이 두 사실이 모순인가?" | **Tier 2** |
| "앨리스는 지난 6개월간 어떻게 변했지?" | **Tier 2** |

**막히면 Tier 1 부터.** 모델을 안 부르고, 100ms 안에 답하고, 매번 같은 답을 준다. Tier 2 는 Tier 1 을
도구로 쥐고 있으므로, Tier 1 이 답할 수 있는 질문에 Tier 2 를 쓰는 것은 같은 답에 돈을 더 내는 것이다.

### Tier 0 — context

```sh
curl -s -G localhost:8080/v1/workspaces/demo/sessions/s1/context "${auth[@]}" \
  --data-urlencode 'tokens=4000'
```

| 파라미터 | 기본값 | 무엇인가 |
|---|--:|---|
| `tokens` | 4000 | 전체 예산. 요약 40% · 원문 60% 로 나뉜다 |
| `target` | session 이름 | 렌더링할 때 누구 시점인지 |
| `perspective` | — | 렌더링 관점 |

응답에는 `summary`, `messages`, 그리고 예산이 실제로 어떻게 쓰였는지가 들어 있다
(`summaryTokens`, `messageTokens`, `tokenBudget`). `representation` 은 그것을 바로 프롬프트에 넣을
수 있게 렌더링한 문자열이다.

예산이 빠듯하면 **가장 오래된 메시지가 떨어진다.** 가장 최근 턴은 잘리지 않는다.

### Tier 1 — recall

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/recall "${auth[@]}" \
  -d '{
    "query": "앨리스는 어디서 일하나",
    "observer": "alice",
    "observed": "alice",
    "limit": 10,
    "explain": true,
    "threshold": 0.2,
    "filter": {"level": "explicit"}
  }'
```

| 필드 | 필수 | 비고 |
|---|:-:|---|
| `query` | 예 | 자연어 질의 |
| `observer`, `observed` | 예 | **어느 쌍의 기억을 볼 것인가.** 기본값 없음 |
| `limit` | 아니오 | 돌려줄 개수. 기본 10, 상한 100 |
| `explain` | 아니오 | 여섯 신호 내역. **기본 켜짐** — 끄려면 `false` 를 명시한다 |
| `threshold` | 아니오 | **융합** 점수 하한. workspace 기본값을 이번 요청만 덮는다 |
| `filter` | 아니오 | [필터](#8-필터) 참고 |

응답:

```json
{
  "analyzedQuery": "앨리스 어디 일하",
  "candidatesConsidered": 42,
  "hits": [{
    "score": 0.7213,
    "conclusion": { "id": "...", "content": "...", "level": "explicit",
                    "timesDerived": 3, "lastReinforcedAt": "..." },
    "explain": { "sem": 0.81, "kw": 0.64, "ent": 0.90, "reinf": 0.58,
                 "rec": 0.99, "lvl": 1.0,
                 "weights": [0.5, 0.22, 0.13, 0.08, 0.05, 0.02],
                 "matchedEntities": ["서울", "강남"] }
  }]
}
```

`analyzedQuery` 는 분석기가 질의를 어떻게 쪼갰는지 보여 준다. 한국어에서 이게 이상해 보이면
[언어 설정](#11-한국어-쓰기)을 확인할 곳이라는 뜻이다.

### 결론 목록 — 순위 없이

순위가 아니라 그냥 전부 필요할 때.

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions "${auth[@]}" \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice' \
  --data-urlencode 'page=0' --data-urlencode 'size=50'
```

### Tier 2 — chat

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/chat "${auth[@]}" \
  -d '{
    "question": "앨리스에 대해 아는 것을 전부 나열해 줘",
    "observer": "bot",
    "observed": "alice",
    "reasoningLevel": "medium",
    "history": [{"role":"user","content":"앞 턴"}]
  }'
```

`reasoningLevel` 이 반복 상한과 도구 세트를 함께 정한다. 기본값 `medium`.

| 레벨 | 최대 반복 | 도구 |
|---|--:|---|
| `minimal` | 1 | `recall` |
| `low` | 3 | + `search_messages` |
| `medium` | 6 | + `grep_messages`, `messages_by_date` |
| `high` | 10 | + `search_temporal`, `reasoning_chain` |
| `max` | 16 | + `entity_provenance` |

응답에는 `answer` 와 함께 `iterations`, `stoppedAtLimit`, 그리고 실제로 무엇을 불렀는지가
`toolCalls` 에 들어 있다. `stoppedAtLimit` 이 `true` 면 모델이 답을 다 만들지 못하고 상한에서
멈춘 것이다 — 레벨을 올리거나 질문을 좁힐 자리다.

`history` 는 최대 500턴까지 받는다. 구조화된 출력이 필요하면 `responseFormat` 에 JSON 스키마를 준다.

스트리밍은 `POST .../chat/stream` 이고 SSE 로 나간다. 타임아웃 5분.

---

## 7. explain 읽는 법

모든 히트가 왜 그 자리에 있는지를 함께 싣는다. **기본으로 켜져 있다** — 튜닝을 내역 없이 하는 것은
짐작이라서, 끄려면 `"explain": false` 를 명시해야 한다. 순위를 튜닝하려면 이걸 읽을 줄 알아야 한다.

```
score = 0.50·sem + 0.22·kw + 0.13·ent + 0.08·reinf + 0.05·rec + 0.02·lvl
```

| 신호 | 무엇을 말하는가 | 낮으면 |
|---|---|---|
| `sem` | 의미가 가까운가 | 임베더가 이 질의를 이 문장과 잇지 못했다 |
| `kw` | 질의 단어가 실제로 나오는가 | 표현이 다르다. 정확히 0 이면 **하나도** 안 나온다 |
| `ent` | 질의가 짚은 이름이 걸려 있는가 | 엔티티가 안 걸렸거나, 너무 많은 곳에 걸려서 할인됐다 |
| `reinf` | 몇 번 확인된 사실인가 | 한 번 나오고 만 사실 |
| `rec` | 얼마나 최근에 확인됐나 | 오래됐다. 반감기가 짧으면 빨리 떨어진다 |
| `lvl` | 얼마나 직접적인가 | `contradiction`(0.6) 이나 `inductive`(0.8) 다 |

### 진단 예시 세 가지

**`sem` 만 높고 `kw` 가 0.** 임베더가 개념적으로 이었지만 단어는 겹치지 않는다. 정상이고, 시맨틱
신호가 제 일을 한 경우다.

**전부 낮은데 `ent` 만 높다.** 엔티티 층이 다른 신호가 놓친 히트를 건져 올렸다. 고유명사가 든
질의에서 자주 보이는 모양이다.

**`sem` 이 이상하게 키워드처럼 움직인다.** 임베딩 제공자를 안 붙였을 가능성이 크다. 자격 증명이
없으면 임베더가 로컬 해싱으로 물러나고, 그때 `sem` 은 두 번째 키워드 신호처럼 군다. 개념적 질의가
특히 약해진다.

### 알아 둘 것 두 가지

**분모는 상수다.** 가중치 합은 1.00 이고 어떤 신호가 발화하지 않아도 그대로다. 빠진 신호는 0 을
낸다 — 점수가 정직하게 떨어지고 후보들 사이의 순서는 안 바뀐다. 그래서 배포가 달라도 점수를 비교할
수 있고, 고정 임계값이 의미를 갖는다.

**`threshold` 는 융합 점수를 자른다.** 시맨틱 점수만 놓고 자르는 것이 아니다. 그렇게 하면 키워드가
정확히 일치한 행을 건져 올리려던 바로 그 순간에 버리게 된다.

---

## 8. 필터

`recall` 과 메시지 검색이 같은 필터 언어를 쓴다.

### 문법

```json
{
  "level": "explicit",
  "times_derived": {"gte": 2},
  "created_at": {"gte": "2026-01-01T00:00:00Z"},
  "OR": [{"session_name": "s1"}, {"session_name": "s2"}]
}
```

- **맨 스칼라는 `eq`**, **맨 리스트는 `in`**. 나머지는 연산자를 이름으로 써야 한다.
- `AND`, `OR`, `NOT` 으로 중첩한다. 최상위의 여러 키는 `AND` 다.

### 연산자

| 연산자 | 의미 |
|---|---|
| `eq`, `ne` | 같다 / 다르다. `ne` 는 null-safe (`IS DISTINCT FROM`) |
| `gt`, `gte`, `lt`, `lte` | 대소 비교 |
| `in`, `nin` | 목록 안 / 밖. `nin` 은 NULL 행을 버리지 않는다 |
| `contains`, `icontains`, `starts_with` | 텍스트 필드에만 |
| `exists` | 값이 boolean. 컬럼이 non-null 인지 |

### 쓸 수 있는 필드 — 허용 목록이다

목록에 없는 이름은 통과되지 않고 **422** 다. 다른 쌍의 스코프가 걸린 컬럼에 필터가 닿는 것을 막는
장치라서, 새 컬럼은 정의상 opt-in 이다.

**결론** (`recall`, `conclusions`):

```
id            session_name   level        content       content_norm   sync_state
confidence    times_derived  created_at   updated_at    last_reinforced_at   expires_at
```

**메시지** (`messages/search`):

```
id   session_name   peer_name   content   token_count   seq_in_session   created_at
```

### 한계

- 중첩 깊이 **16**
- 술어 개수 **256**

둘 다 방어 장치다. 깊이 제한이 없으면 중첩된 객체 몇백 킬로바이트가 스택을 넘겨 요청 스레드를
죽인다 — `StackOverflowError` 는 `RuntimeException` 이 아니라서 오류 처리를 통째로 빠져나간다.
개수 제한이 없으면 얕지만 항이 5만 개인 `OR` 이 데이터베이스에 플랜을 요구한다.

### 타입 강제는 엄격하다

`{"times_derived": {"gte": "많이"}}` 는 0 이 아니라 **422** 다.

같은 이유로 **거부된 필터는 200 에 빈 결과가 아니라 422** 다. 요청은 잘 파싱됐고 술어가 문제다.
빈 결과를 주면 호출자가 "데이터가 없구나"라고 결론짓는데, 실제로는 질의가 버려진 것이다.

---

## 9. 프로버넌스와 감사 추적

**"이건 어디서 나온 얘기죠?"** 에 답하는 세 가지 라우트.

### 엔티티에서 거꾸로

```sh
curl -s -G localhost:8080/v1/workspaces/demo/recall/provenance "${auth[@]}" \
  --data-urlencode 'entity=서울' \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice' \
  --data-urlencode 'limit=10'
```

```
엔티티 → 걸린 결론들 → 각 결론의 전제 → 원문 메시지
```

찾을 수 없는 전제는 `unresolvedPremiseIds` 에 따로 실린다. 조용히 빠지지 않는다.

### 결론 하나의 내역

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions/{id}/events "${auth[@]}" \
  --data-urlencode 'limit=100'
```

`actor`(누가), `event`(무엇을), `beforeContent` / `afterContent`(어떻게 바뀌었는지)가 함께 나온다.

| actor | 누구인가 |
|---|---|
| `deriver` | 메시지에서 결론을 뽑은 것 |
| `dreamer` | 아무도 안 볼 때 추론한 것 |
| `dedup` | 중복 제거가 강화·대체한 것 |
| `api` | 사람이 직접 호출한 것 |
| `reconciler` | 임베딩 백필, 만료 청소, 큐 정리 |

| event | 무슨 일이 있었나 |
|---|---|
| `add` `reinforce` `replace` | 만들어짐 / 다시 확인됨 / 더 나은 표현으로 교체됨 |
| `delete` `expire` `restore` | 지워짐 / 만료됨 / 되살아남 |
| `sync_failed` | 임베딩 호출이 실패해서, 재시도 전까지 시맨틱 recall 에 안 보임 |

`sync_failed` 를 특히 볼 것. 그 결론은 존재하지만 `sem` 신호에 잡히지 않는다.

### 추론 사슬

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions/{id}/chain "${auth[@]}" \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice'
```

이 결론을 딛고 있는 전제들을 따라 올라간다. `deductive` / `inductive` 결론에서 의미가 있다.

---

## 10. workspace 튜닝

```sh
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"language":"ko","recall.half_life_days":30}}'
```

`PUT` 은 **통째로 교체한다.** 부분 갱신이 아니다.

### 설정 키 전부

| 키 | 기본값 | 범위 | 무엇을 하는가 |
|---|--:|---|---|
| `language` | `und` | 문자열 | 분석기를 고른다. `ko` / `en` / 그 외는 bigram |
| `recall.weights` | `[.50 .22 .13 .08 .05 .02]` | 여섯 수, 합 1.00 | 순서는 `[sem, kw, ent, reinf, rec, lvl]` |
| `recall.half_life_days` | 180 | (0, 100000] | 최신성 신호가 절반이 되는 기간 |
| `recall.threshold` | 0 | [0, 1] | **융합** 점수 하한 |
| `recall.oversample` | 4 | [1, 100] | 각 신호 경로가 융합 전에 가져오는 후보 배수 |
| `recall.entity_top_k` | 10 | [1, 1000] | 부스트에 쓸 엔티티 이웃 수 |
| `recall.entity_sim_cut` | 0.5 | [0, 1] | 이 밑의 엔티티 매치는 0 을 낸다 |
| `dedup.cosine_distance_max` | 0.05 | [0, 2] | 3단계가 볼 최대 거리 |
| `dedup.unique_token_weight` | 10 | [0, 1000] | 정보량 = 토큰수 + 가중치 × 서로 다른 토큰수 |
| `batch.token_threshold` | 512 | [0, 1000000] | 이만큼 쌓이면 배치가 나간다 |
| `batch.max_age_minutes` | 30 | [0, 10080] | 이만큼 지나면 나간다 |
| `batch.idle_flush_seconds` | 3 | [0, 3600] | 이만큼 조용하면 나간다 |
| `observe_me` | `true` | boolean | 말한 사람이 자기를 기억하는가 |
| `observe_others` | `true` | boolean | 듣는 사람이 말한 사람을 기억하는가 |

배치 세 값은 0 을 허용한다. `or` 게이트라서 하나를 0 으로 두면 항상 참이 되고, 그게 배칭을 끄는
방법이다.

### 두 가지 함정

**모르는 키는 422 다.** 오타가 조용히 저장되는 것보다 낫다. `recall.half_life` 를 쓰면 예전에는
깨끗이 저장되고 응답 본문에 그대로 실려 돌아왔지만 아무것도 바꾸지 않았다.

**튜닝 키를 peer 나 session 에 쓰면 422 다.** peer 와 session 에도 `configuration` 컬럼이 있지만
튜닝은 **workspace 것만** 읽는다.

```sh
# 이건 422 다
curl -s -X PUT localhost:8080/v1/workspaces/demo/peers/alice/configuration "${auth[@]}" \
  -d '{"configuration":{"recall.half_life_days":5}}'
```

peer 나 session 의 `configuration` 에 **모르는** 키를 넣는 것은 여전히 허용된다. 그건 그냥 불투명한
클라이언트 데이터이고, 무언가를 튜닝한다고 주장하지 않기 때문이다.

### 언제 무엇을 바꾸나

| 상황 | 바꿀 것 |
|---|---|
| 코딩 에이전트라 몇 주면 잊어야 한다 | `recall.half_life_days` 를 30 정도로 |
| recall 이 잡음을 너무 많이 물어 온다 | `recall.threshold` 를 올린다 |
| 대화형이 아니라 배치 수집이다 | `batch.idle_flush_seconds` 를 0 으로 |
| 큰 방을 여는데 비용이 걱정된다 | `observe_others` 를 `false` 로 |
| 가중치를 바꾸고 싶다 | **평가 세트가 그러라고 할 때만.** 감으로는 안 된다 |

---

## 11. 한국어 쓰기

```sh
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"language":"ko"}}'
```

이걸로 Nori 형태소 분석이 켜진다. 태그는 접두사로 맞춰 본다 — `ko`, `ko-KR` 다 된다. 모르는 태그는
예외를 던지지 않고 bigram 으로 물러난다.

### 언제 설정해야 하는가

**되도록 일찍.** `content_analyzed` 는 **쓰기 시점에** 만들어진다. 나중에 언어를 바꾸면 이미 있는
행은 옛 분석기로 쪼개진 채 남는다.

다행히 그건 마이그레이션이 아니라 **재색인**이다. 절차는 [런북의 재색인
절](runbook.md#재색인)에 있다.

### 고유명사가 자꾸 쪼개진다면

Nori 사용자 사전을 붙인다.

```sh
export AIMON_MEMORY_NORI_USER_DICT=/path/to/userdict.txt
```

### 확인하는 법

recall 응답의 `analyzedQuery` 가 분석기가 질의를 어떻게 쪼갰는지 보여 준다. 이게 이상하면 언어
설정부터 볼 자리다.

---

## 12. dream 과 peer card

### dream 예약하기

Dreamer 는 보통 알아서 돈다. 지금 돌리고 싶으면,

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/dreams "${auth[@]}" \
  -d '{"observer":"bot","observed":"alice","type":"consolidate"}'

curl -s -G localhost:8080/v1/workspaces/demo/dreams "${auth[@]}" \
  --data-urlencode 'observer=bot' --data-urlencode 'observed=alice'
```

| `type` | 무엇을 하는가 |
|---|---|
| `consolidate` | 연역·귀납·모순 탐색. 새 결론을 만든다 |
| `card_refresh` | peer card 만 다시 만든다 |

목록의 `status`, `produced`, `error` 로 결과를 본다.

### peer card

한 peer 를 다른 peer 가 본 압축 프로필.

```sh
curl -s -G localhost:8080/v1/workspaces/demo/peer-card "${auth[@]}" \
  --data-urlencode 'observer=bot' --data-urlencode 'observed=alice'

curl -s -X POST localhost:8080/v1/workspaces/demo/peer-card/refresh "${auth[@]}" \
  -d '{"observer":"bot","observed":"alice"}'
```

각 줄은 네 접두사 중 하나를 단다.

```
IDENTITY:      ATTRIBUTE:      RELATIONSHIP:      INSTRUCTION:
```

형식이 깨진 생성물은 저장되지 않고 버려진다. 카드가 비어 있다면 모델이 형식을 무시했다는 뜻이다.

카드 갱신은 일부러 싸게 만들어 두었다 — dreamer 의 스케줄링 카운터를 건드리지 않으므로, 카드를
자주 새로 만들어도 진짜 추론 패스의 예산을 먹지 않는다.

---

## 13. 에러 사전

응답 본문은 언제나 `{"code": "...", "message": "..."}` 다.

| 상태 | 언제 | 무엇을 봐야 하나 |
|--:|---|---|
| **400** | 본문이 JSON 이 아니다, 파라미터 타입이 틀렸다 | 요청 자체 |
| **401** | 토큰이 없거나 검증되지 않는다 | `Authorization: Bearer`, 서명 키, 만료 |
| **403** | 토큰이 이 라우트·workspace·peer·session 에 못 닿는다 | 스코프와 좁히기 |
| **404** | 그런 엔드포인트나 리소스가 없다 | 경로, 그리고 인증은 통과했는지 |
| **405** | 메서드가 틀렸다 | GET/POST/PUT/DELETE |
| **409** | 제약 위반 | 중복 키, 동시 쓰기 |
| **422** | **파싱은 됐는데 값이 문제다** | 필터 필드·타입, 설정 키·범위 |
| **503** | 모델·임베딩 제공자가 설정되지 않았다 | `AIMON_MEMORY_LLM_PROVIDER`, API 키 |
| **500** | 그 외 | 로그. 이건 버그다 |

### 자주 보는 코드

| `code` | 상태 | 뜻 |
|---|--:|---|
| `bad_configuration` | 422 | 모르는 설정 키, 범위 밖의 값, 합이 1.00 이 아닌 가중치 |
| `bad_filter` | 422 | 허용 목록에 없는 필드, 타입이 안 맞는 값, 깊이·개수 초과 |
| `unauthorized` | 401 | 토큰이 없거나 검증되지 않는다 |
| `forbidden` | 403 | 스코프나 쌍이 안 맞는다 |
| `not_found` | 404 | 그런 엔드포인트나 리소스가 없다 |
| `method_not_allowed` | 405 | 메서드가 틀렸다 |
| `bad_scope` | 400 | 그 스코프에 필요한 `workspace` / `peer` / `session` 이 본문에 없다 |
| `bad_lifetime` | 400 | 30일 초과, 또는 0 이하 |
| `bad_reasoning_level` | 400 | `minimal` `low` `medium` `high` `max` 중 하나가 아니다 |
| `batch_too_large` | 400 | 한 요청에 메시지 100건 초과 |
| `bad_level` | 400 | 결론 등급이 네 값 중 하나가 아니다 |
| `llm_not_configured` | 503 | 모델 제공자 없이 Tier 2 나 도출을 요구했다 |
| `fixture_miss` | 503 | replay 모드인데 기록된 픽스처에 없는 호출이다 |
| `missing_config` | 503 | 필수 설정이 없다. `AIMON_MEMORY_JWT_SECRET` 이라면 애초에 기동에서 실패한다 |
| `weak_jwt_secret` | — | 서명 키가 32바이트 미만. HTTP 가 아니라 기동에서 실패한다 |

422 메시지는 **허용되는 값을 함께 알려 준다.** 모르는 설정 키를 쓰면 열네 개 키 전부를, 없는 필터
필드를 쓰면 그 스키마의 필드 전부를 나열해 준다. 문서를 다시 열기 전에 오류 본문부터 읽을 것.

### 403 이 나오는데 이유를 모르겠다면

순서대로 확인한다.

1. 토큰 스코프가 이 라우트의 최소 스코프를 만족하는가 (`openapi.json` 의 각 라우트 `description`)
2. 경로의 workspace / peer / session 에 토큰이 닿는가
3. **`observer` 가 토큰의 peer 와 맞는가** — 이게 가장 자주 놓치는 것이다

3번을 설명하면. `recall`, `conclusions`, `chat`, `dreams`, `peer-card` 는 observer 와 observed 를
경로가 아니라 **본문이나 쿼리스트링**에 받는다. 인증 인터셉터는 경로 변수만 본다. 그래서 별도 검사가
observer 쪽을 막는다 — **밥의 토큰으로 앨리스의 기억을 읽을 수 없다.** observed 쪽은 일부러 검사하지
않는다. 누군가를 기억하는 것은 그 사람이 허락하는 종류의 일이 아니기 때문이다.

---

## 14. 자주 하는 실수

**recall 에 `observer` / `observed` 를 안 넣는다.** 기본값이 없다. 어느 쌍의 기억을 볼 것인지는
반드시 말해야 한다. 자기 자신에 대한 기억이라면 둘을 같게 준다.

**메시지를 넣고 바로 recall 을 친다.** 도출은 비동기다. `?wait=derive` 를 쓰거나 배치 게이트를
기다린다.

**결론이 안 생기는데 워커를 안 띄웠다.** API 는 큐에 넣기만 한다. 도출은 워커가 한다.

**모델 제공자 없이 Tier 2 를 부른다.** 503 이다. 자격 증명이 없으면 제공자는 completion 을
요청받는 순간 분명한 메시지로 실패한다.

**튜닝 키를 peer 에 쓴다.** 422 다. 튜닝은 workspace 것만 읽는다.

**`PUT /configuration` 을 부분 갱신으로 안다.** 통째로 교체한다. 유지하고 싶은 키를 함께 보낼 것.

**언어를 나중에 바꾸고 기존 데이터가 따라오길 기대한다.** `content_analyzed` 는 쓰기 시점에
만들어진다. 재색인이 필요하다.

**10명짜리 방을 기본값으로 연다.** 배치마다 100번 부른다. `observe_others` 를 보라.

**필터에 없는 필드를 쓰고 200 에 빈 결과를 기대한다.** 422 다. 그게 낫다 — 빈 결과였다면 데이터가
없다고 결론지었을 것이다.

**actuator 포트를 공개한다.** 8080 만 공개한다. 9090 과 9091 은 인증이 없다. 인증 인터셉터는
`/v1/**` 만 덮는다.

**운영에서 `AIMON_MEMORY_OPENAPI=true` 를 켠다.** 토큰 없이 `/v3/api-docs` 가 열린다. 커밋된
`docs/openapi.json` 이 같은 문서이고 그쪽은 버전이 붙어 있다.

**첫 admin 토큰을 `/v1/tokens` 로 만들려고 한다.** 그 라우트는 좁히기만 한다. 첫 토큰은 직접
서명해야 한다.

---

## 15. aimon-core 에서 쓰기

aimon-core 애플리케이션이라면 HTTP 를 직접 칠 필요가 없다. `aimon-memory-client` 가 어댑터다.

```java
PeerMemory memory = new RemotePeerMemory(RemoteMemoryOptions.builder()
        .baseUri("https://memory.internal:8080")
        .token(tokens::current)          // 요청마다 호출된다. 토큰은 만료되니까
        .agentPeer("assistant")          // ASSISTANT 역할 메시지를 누구 이름으로 저장할지
        .build());
```

| aimon-core 티어 | 엔드포인트 |
| --- | --- |
| `SNAPSHOT` | `GET /v1/workspaces/{ws}/conclusions` |
| `SEARCH` | `POST /v1/workspaces/{ws}/recall` |
| `CHAT` | `POST /v1/workspaces/{ws}/chat` |
| `OBSERVE` | `POST /v1/workspaces/{ws}/conclusions` |
| `INGEST` | `POST /v1/workspaces/{ws}/sessions/{session}/messages` |

쌍은 번역 없이 그대로 건너온다. aimon-core 의 subject 가 observed 이고 observer 가 observer 다.
observer 를 지정하지 않은 질의는 subject 자신의 self-pair 를 가리킨다.

능력 신호 세 개는 `false` 이고, 셋 다 빠뜨린 것이 아니라 실제로 다른 점이다.

- `narrowsBySession()` — 결론은 그것을 만든 세션보다 오래 살기 때문에 recall 은 세션으로 좁히지 않는다
- `storesConfidence()` — 주입된 관측의 confidence 는 받아 적는 것이 아니라 등급과 강화 횟수에서 나온다
- 수집은 도출하지 않고 큐에 넣으므로 접수증에 `derived` 가 실리는 일이 없다

경계 전체는 [ADR 0007](adr/0007-aimon-core-boundary.md) 에 있다.

---

## 다음에 읽을 것

- [개념 문서](concepts.md) — 왜 이런 모양인지
- [아키텍처](architecture.md) — 목표·제약·컨텍스트·빌딩블록·품질 관문·리스크 (arc42 12절)
- [`openapi.json`](openapi.json) — 라우트 33개의 정확한 스키마와 스코프
- [런북](runbook.md) — 배포, 관측, 뭔가 잘못됐을 때
- [ADR](adr/README.md) — 구현이 명세를 벗어난 자리와 그 근거
