**한국어** · [English](README.en.md)

# aimon-memory

[![ci](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/kangwoo/aimon-memory/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](gradle/libs.versions.toml)
[![docs](https://img.shields.io/badge/docs-kangwoo.github.io-blue.svg)](https://kangwoo.github.io/aimon-memory/)

대화형 에이전트를 위한 메모리 시스템. 저장하는 모든 사실은 방향이 있는
**(observer, observed) 쌍**에 속한다 — `alice` 가 자기 자신을 기억한 내용과 `bot` 이 `alice` 를
기억한 내용은 서로 섞이지 않는 별개의 저장소다.

HTTP API 뒤에서 두 개의 프로세스로 돈다.
[`aimon-memory-client`](modules/aimon-memory-client) 가
[aimon-core](https://github.com/kangwoo/aimon-core) 의 `PeerMemory` 를 이 서비스 위에 구현하므로,
aimon-core 애플리케이션은 조립하는 `PeerMemory` 만 바꾸면 메모리 백엔드가 이쪽으로 바뀐다.

`aimon-memory-design.md` 와 `aimon-memory-build-plan.md` 를 보고 만들었다.

아키텍처 서술은 [`docs/architecture.md`](docs/architecture.md) 에 있다 — 컨텍스트 다이어그램, 모듈,
품질 관문, 리스크까지 arc42 12절로.

---

## 무엇을 하는가

메시지는 HTTP 로 들어오고 즉시 응답한다. 워커가 메시지를 묶어 오래 남을 사실을 뽑고, 이미 아는 것과
비교해 중복을 걷어낸 뒤, 관측하던 쌍 아래에 결과를 넣는다.

추출은 배치당 한 번이 아니라 **관측하는 쌍마다 한 번** 돈다. 프롬프트를 observer 쪽에서 쓰기 때문이고,
그래서 큰 방을 여는 비용이 관측자 수를 따라간다 ([ADR 0006](docs/adr/0006-fanout-cost.md)).

읽기는 3티어다.

| 티어 | 호출 | 모델 호출 | 통상 지연 | 결정적 |
|---|---|--:|--:|:-:|
| 0 | `context()` | 0 | ~50 ms | 예 |
| 1 | **`recall()`** | 0 | ~100 ms | 예 |
| 2 | `chat()` | 1–16 | 초 단위 | 아니오 |

**Tier 1 이 이것을 만든 이유다.** 메모리 시스템에 던지는 질문은 대부분 조회이고, 조회는 빠르고 싸고
매번 같은 답이어야 한다. 고정 가중치 아래 여섯 신호로 순위를 매기고, 모든 응답이 그 점수를 만든 내역을
함께 싣는다. 골든 픽스처가 그 전부를 소수 6자리까지 검사한다
([`concepts.md` §9](docs/concepts.md#9-여섯-신호와-융합-공식)).

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
아티팩트에 손을 뻗지 않는다.

계약 계층도 마찬가지다. `:aimon-memory-client:contractTest` 는 `at.aimon.core:aimon-memory-testkit`
을 상속하는데, 이 아티팩트는 aimon-core 0.3.0 에 처음 담겨 나가므로 아직 릴리스가 없다. 대신
Central 의 스냅샷 저장소에 올라가 있고, 빌드가 그 좌표 하나만 거기서 풀도록 열어 뒀다. 그래서 이
계층도 클론한 다음 바로 돈다 — 손으로 publish 할 것이 없다.

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

라우트 서른세 개 전부가 [`docs/openapi.json`](docs/openapi.json) 에 있다. 요청·응답 스키마와 라우트마다
필요한 토큰 스코프까지 들어 있다. 브라우저로 훑고 싶으면 아무 Swagger UI 에나 저 파일을 물리면 된다.
실행 중인 서비스에서 받고 싶으면 `AIMON_MEMORY_OPENAPI=true` 로 띄우면 `/v3/api-docs` 가 열린다 —
기본값은 꺼짐이고, 인증 인터셉터가 `/v1/**` 만 덮기 때문이다.

## 갖다 쓰기

**아직 Maven Central 에 올라간 아티팩트가 없다.** `VERSION_NAME` 은 `0.1.0-SNAPSHOT` 이고, 릴리스는
`v0.1.0` 같은 태그가 트리거한다. 그때까지는 로컬에 발행해서 쓴다.

```sh
./gradlew publishToMavenLocal
```

aimon-core 애플리케이션이 필요한 것은 어댑터 하나다.

```kotlin
repositories {
    mavenLocal()          // 첫 릴리스가 나가면 mavenCentral() 하나로 충분하다
}

dependencies {
    implementation(platform("at.aimon.memory:aimon-memory-bom:0.1.0-SNAPSHOT"))
    implementation("at.aimon.memory:aimon-memory-client")
}
```

Maven 이라면 BOM 을 `dependencyManagement` 로 import 한다.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>at.aimon.memory</groupId>
      <artifactId>aimon-memory-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

`aimon-memory-client` 는 **aimon-core 말고 아무것도 의존하지 않는다.** 원격 메모리를 쓰겠다는
애플리케이션의 클래스패스에 pgvector, Flyway, Lucene, Spring Boot 애플리케이션이 딸려 들어가서는 안
되고, 다섯 티어 중 그것들을 필요로 하는 것도 없다. 바이트코드는 Java 17 이다 — aimon-core 의 바닥이고,
이 모듈이 컴파일을 맞추는 유일한 대상이다.

조립하는 코드와 티어별 엔드포인트는
[`docs/guide.md` §15](docs/guide.md#15-aimon-core-에서-쓰기) 에 있다.

### BOM 이 왜 있나

버전을 의존성 줄마다 되풀이하지 않기 위해서다. `java-platform` 이고, 두 가지가 의도적이다.

**자기 모듈만 제약하고 서드파티 버전은 건드리지 않는다.** 여기서 Spring Boot 나 Jackson 버전을 못
박으면 도움이 되는 것처럼 보이고 실제로는 해롭다 — Gradle 은 `platform()` 의 버전을 권고로 다루지만
Maven 의 `dependencyManagement` 와 `enforcedPlatform` 은 덮어쓰기로 다룬다. 이 BOM 을 import 한 Maven
애플리케이션의 Boot 관리 버전이 이 저장소가 빌드했던 값으로 조용히 바뀌게 된다.

**목록은 손으로 적지 않고 빌드가 뽑는다.** 손으로 유지하는 BOM 은 한 릴리스씩 뒤처지는 BOM 이다.
게다가 검사는 뽑아낸 목록이 아니라 각 모듈이 `gradle.properties` 에 선언한 좌표를 읽는다 — 자기가 만든
목록을 자기와 비교하면 목록이 자기 자신과 같다는 것만 확인하게 되기 때문이다. 그래서 절반만 발행된 두
경우가 잡힌다. 플러그인만 붙이고 좌표를 선언하지 않은 모듈, 좌표만 선언하고 플러그인을 안 붙인 모듈.

### 발행되는 것과 아닌 것

| | |
|---|---|
| **발행** | `aimon-memory-bom` · `-client` · `-core` · `-embed` · `-engine` · `-llm` · `-recall` · `-store` · `-text` |
| 발행 안 함 | `aimon-memory-api` · `-worker` — 라이브러리가 아니라 실행 프로세스다. [실행하기](#실행하기) 참고 |
| 발행 안 함 | `aimon-memory-testkit` — 이 저장소의 테스트 하네스다 |

서비스를 띄워 놓고 HTTP 로만 쓸 거라면 의존성이 아예 필요 없다. 라우트는
[`docs/openapi.json`](docs/openapi.json), 호출법은 [`docs/guide.md`](docs/guide.md) 에 있다.

## 문서

전부 **<https://kangwoo.github.io/aimon-memory/>** 에 올라가 있다. 검색과 언어 전환이 붙어 있고,
`main` 에 들어간 것이 그대로 배포된다. 아래 목록은 GitHub 에서 바로 읽을 때를 위한 것이다.

정본은 한국어다. 모든 문서는 같은 경로에 `.en.md` 접미사를 붙인 영어판을 함께 둔다 — 이 문서의 영어판은
[`README.en.md`](README.en.md) 다. 명세 두 건만 예외로 한국어만 있고, 왜 그런지와 각 절이 어디 있는지는
[`docs/spec/README.md`](docs/spec/README.md) 에 적어 뒀다.

문서마다 **소유하는 것이 정해져 있다.** 같은 사실이 두 곳에 나오면 한쪽은 요약이고, 요약에는 정본
링크가 붙는다 ([ADR 0008](docs/adr/0008-arc42-architecture-doc.md)).

| 문서 | 무엇을 소유하는가 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | **아키텍처 서술.** 목표·제약·컨텍스트·빌딩블록·품질 관문·리스크. arc42 12절 |
| [`docs/concepts.md`](docs/concepts.md) | **개념.** 쌍, 세 티어, 여섯 신호, 중복 제거, 망각이 왜 이렇게 생겼는지 |
| [`docs/guide.md`](docs/guide.md) | **사용자 가이드.** 토큰 발급부터 recall 튜닝까지, `curl` 로 따라가는 길 |
| [`docs/runbook.md`](docs/runbook.md) | 배포, 튜닝, 뭔가 잘못됐을 때 볼 것 |
| [`docs/adr/`](docs/adr/README.md) | 결정 기록. 명세에서 벗어난 자리와 그 근거 |
| [`docs/openapi.json`](docs/openapi.json) | 라우트 33개, 스키마, 스코프. 테스트가 코드와 붙들어 둔다 |
| [`docs/spec/`](docs/spec/README.md) | 냉동된 명세와 계획 (2026-08-31) |
| [`docs/dashboards/`](docs/dashboards) | Grafana 대시보드 `aimon-memory-overview.json` |
| [`test-fixtures/README.md`](test-fixtures/README.md) | 픽스처 코퍼스 두 벌과 각각이 증명하는 것 |

aimon-core 쪽에서 왔다면 [ADR 0007](docs/adr/0007-aimon-core-boundary.md) 부터. 두 저장소 사이의
경계를 긋는 문서다.

## 라이선스와 기여

Apache-2.0 — [LICENSE](LICENSE). 이것을 가져다 쓰는 aimon-core 와 같은 라이선스이고,
[ADR 0005](docs/adr/0005-agpl-boundary.md) 의 클린룸 경계가 지켜 내려는 것이 바로 이 라이선스다.

- [CONTRIBUTING.md](CONTRIBUTING.md) — 변경을 제안하는 방법과 그것이 통과해야 할 관문
- [SECURITY.md](SECURITY.md) — 취약점 신고 방법
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — 참여하는 사람에게 기대하는 것
- [CHANGELOG.md](CHANGELOG.md) — 릴리스별 변경 내역
