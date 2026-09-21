**한국어** · [English](0001-stack.en.md)

# ADR 0001 — 스택

**상태:** accepted · 2026-08-31

## 결정

Java 21 · 가상 스레드 위의 Spring Boot 3.5 MVC · PostgreSQL 16 + pgvector · Flyway · Gradle
멀티모듈 · Lucene 분석기 · jtokkit.

## Java 25 가 아니라 Java 21

계획은 Java 25 를 지정한다. 가상 스레드(virtual thread)는 21부터 정식 기능이고, 설계가 실제로 기대는
언어 기능은 그것 하나뿐이다. 21 은 LTS 이고 도구 생태계도 이미 자리를 잡았다.

버전은 `gradle/libs.versions.toml` 의 한 줄이다. 25 로 옮기는 일은 그 한 줄에 CI 이미지 하나를 올리는
것이 전부다. 21 을 겨냥해 쓴 코드는 없다.

## WebFlux 가 아니라 가상 스레드 위의 Spring MVC

설계 문서는 WebFlux 라고 했다. 여기 있는 핸들러는 거의 전부 블로킹 JDBC 호출 몇 개를 잇는 짧은
순서이고, 오래 기다리는 하나 — dialectic — 도 소켓을 기다린다. 둘 중 어느 쪽에 멈춰 선 가상 스레드든
수백 바이트면 된다.

WebFlux 는 같은 동시성을 사 주는 대신 모든 스택 트레이스와 모든 디버깅 시간, 그리고 존재하지도 않는
리액티브 JDBC 이야기로 값을 받는다. `spring.threads.virtual.enabled=true` 는 동시성을 가져오면서
코드는 위에서 아래로 읽히게 둔다.

## Postgres 하나로

스토어는 셋이 아니라 하나다. 엔티티 계층은 외래키로 이어진 평범한 테이블 두 개이고, 그래프 DB 를
들여올 이유가 되는 일을 그것이 한다. 벡터·전문 검색·관계 무결성이 한 트랜잭션 안에 있고, 그래서
"결론을 넣고, 그 엔티티를 잇고, 감사 이벤트를 쓴다"가 전부 되거나 전부 안 되는 하나의 연산이 된다.

## 덧붙임 · 2026-09-20 — Boot 4 로 올렸고, 위의 "3.5" 는 이제 거짓이다

결정은 그대로이고 다시 쓰지 않는다. 이 ADR 이 고른 것은 *WebFlux 가 아니라 가상 스레드 위의 Spring
MVC* 였고, Boot 4 는 그 둘을 다 그대로 둔다. 더는 참이 아닌 것은 결정 줄에 적힌 숫자 하나다.

| 위에서 | 지금 |
|---|---|
| "Spring Boot 3.5 MVC" | Spring Boot 4.1.1 MVC. `spring.threads.virtual.enabled=true` 는 그대로다. |

올린 이유는 springdoc 이다. springdoc 3.x 는 `spring-boot-webmvc` 와 `spring-boot-health` 에 기대는데
Boot 3.5 클래스패스에는 그 모듈이 없어서, 2.x 에 머무는 동안 3.x 는 `.github/dependabot.yml` 에서 막혀
있었다. Boot 4 가 그 핀의 조건을 없앴고, 둘은 같은 변경에서 함께 움직였다.

옮기면서 실제로 든 비용은 세 가지였고, 전부 Boot 4 가 모듈을 쪼갠 결과다. 테스트 슬라이스가
`spring-boot-starter-test` 밖으로 나가서 `@AutoConfigureMockMvc` 와 — `@AutoConfigureObservability`
가 metrics 와 tracing 으로 갈라진 뒤의 — `@AutoConfigureMetrics` 를 쓰는 모듈이 그 아티팩트를 이름으로
불러야 했다. Jackson 은 3(`tools.jackson`)이 기본이 되면서 Jackson 2 의 자동 구성이 별도 모듈로 빠졌고,
`ChatController` 가 주입받는 `ObjectMapper` 가 사라졌다 — 이 빌드의 일곱 모듈이 Jackson 2 를 쓰므로
`spring-boot-jackson2` 로 빈을 되살렸지, 일곱 모듈을 옮기지 않았다. HTTP 메시지 변환은 Boot 4 의
기본값인 Jackson 3 에 그대로 둔다. 그리고 Spring Framework 7 이 RFC 9110 을 따라 422 의 이름을
`UNPROCESSABLE_ENTITY` 에서 `UNPROCESSABLE_CONTENT` 로 바꿨다 — 상태 코드도 응답 본문도 그대로이고,
바뀐 것은 상수 이름뿐이다.

623개 테스트가 전부 통과한다. `docs/openapi.json` 은 한 바이트도 바뀌지 않았고, `OpenApiSpecTest` 가
그것을 확인한다.

## 덧붙임 · 2026-09-21 — 일곱 모듈을 옮겼고, 위의 "옮기지 않았다" 는 이제 거짓이다

바로 위 덧붙임은 `spring-boot-jackson2` 로 Jackson 2 의 `ObjectMapper` 빈을 되살린 이유를 "이 빌드의
일곱 모듈이 Jackson 2 를 쓰므로 빈을 되살렸지, 일곱 모듈을 옮기지 않았다" 라고 적었다. 그 문장은 그때
맞았다. 지금은 그 일을 따로 했다.

| 위에서 | 지금 |
|---|---|
| "`spring-boot-jackson2` 로 빈을 되살렸지, 일곱 모듈을 옮기지 않았다" | 일곱 모듈이 Jackson 3 을 쓴다. `spring-boot-jackson2` 는 빠졌다. |

Java 파일 37개, 모듈 8개. 대부분은 임포트 치환이다. 애너테이션은 손대지 않았다 — Jackson 3 의
`jackson-databind` 는 여전히 `com.fasterxml.jackson.core:jackson-annotations` 에 의존하므로
`@JsonIgnoreProperties` 임포트 두 줄은 그대로다. Jackson 3 은 매퍼를 불변으로 만들어
`ObjectMapper.configure(...)` 가 없어졌으므로 `Json` 과 `OpenApiSpecTest` 의 매퍼가
`JsonMapper.builder()` 로 옮겼다. `JsonProcessingException` 이 `JacksonException` 이 되면서 검사 예외가
아니게 됐지만 catch 는 지우지 않고 타입만 바꿨다 — 그 블록들이 하는 일은 Jackson 예외를 이 도메인의
`LlmException`·`StoreException` 으로 바꾸는 것이고, 그건 여전히 필요하다. `JsonNode.fields()` 와
`fieldNames()` 는 사라져서 `properties()`·`propertyNames()` 가 됐고, 이쪽은 `Iterator` 가 아니라
`Collection` 을 주므로 `forEachRemaining` 두 곳이 `forEach` 가 되고 나머지 한 곳은
`declared.addAll(node.path("properties").propertyNames())` 로 접혔다.

**치환이 아닌 것이 하나 있다.** `asText()` → `asString()` 129곳은 이름 변경이 아니다. Jackson 3 은
`asString`·`asInt`·`asBoolean` 계열을 **"강제변환하되 안 되면 zero value"에서 "강제변환하되 안 되면
예외"로 재정의**했다. 두 버전의 jar 로 같은 코드를 돌려 확인한 표다.

| 노드 | Jackson 2 | Jackson 3 |
|---|---|---|
| object·array `.asText()` / `.asString()` | `""` | **`JsonNodeException`** |
| null 노드 | `"null"` | `""` |
| object `.asText("d")` / `.asString("d")` | `""` | `"d"` |
| `"4.9".asInt()` | `4` | **예외** |
| `"hi".asInt()` · `true.asInt()` | `0` · `1` | **예외** |
| `"hi".asBoolean()` | `false` | **예외** |

`asInt`·`asDouble`·`asBoolean` 은 이름이 바뀌지 않아 이 변경의 diff 에 아예 나타나지 않는다. 그런데도
똑같이 바뀌었다. 그게 이 이전에서 가장 놓치기 쉬운 부분이었고, 실제로 리뷰 전까지 아무도 보지 않았다.

**엄격해진 것 자체는 옳다.** 제공자가 엉뚱한 모양으로 답했는데 빈 문자열로 읽는 쪽이 틀렸다. 그대로
둘 수 없는 것은 그 예외가 떨어지는 위치다. `JsonNodeException` 은 `RuntimeException` 이면서
`LlmException` 이 아니고, `FallbackChatBackend.run` 은 `LlmException` 만 잡는다 — 감싸지 않으면 제공자
하나의 응답 모양이 바뀌었을 때 다음 제공자로 넘어가는 대신 요청 전체가 죽는다. 위로 올라가서는
`ApiExceptionHandler` 의 `MemoryException` 분기도 놓쳐 코드 없는 500 이 된다. Jackson 2 에서는 같은
응답이 빈 답변으로 degrade 돼 아무 일도 없었던 것처럼 지나갔다. 셋 중 어느 것도 이 시스템이 약속한
동작이 아니다.

그래서 엄격함은 두고 경계를 만들었다. `Json.shaped`(llm)·`MemoryHttp.shaped`(client) 가 Jackson 의
예외를 각 모듈이 이미 갖고 있던 타입 — `LlmException("bad_json")`, `RemoteMemoryException` — 으로
옮긴다. `OpenAiEmbedder` 는 일부가 0 인 벡터를 저장하는 대신 `EmbeddingException` 을 던지고,
`EvaluationSet` 은 어느 픽스처인지 이름을 담는다. `bad_json` 은 `llm_rejected` 가 아니므로 fallback
체인이 다음 시도로 넘어간다 — 제공자 여럿 중 하나를 읽을 수 없을 때 일어나야 할 일이 그것이다.
`ProviderShapeDriftTest` 가 이것을 고정하며, failover 케이스는 감싸는 코드를 빼면 실패한다는 것을
확인하고 넣었다.

**엄격함이 멈추는 자리가 한 곳 있다: 제공자의 토큰 카운트다.** 나머지 모든 읽기는 "빈 답변으로 읽히는
것이 답변이 없는 것보다 나쁘다" 를 근거로 예외를 던지지만, 토큰 카운트에는 그 근거가 서지 않는다.
그건 텔레메트리다 — 같이 온 completion 의 내용을 하나도 바꾸지 않고, `usage` 블록이 아예 없을 때 이
코드는 이미 `0` 으로 읽는다. 즉 `0` 은 오류를 삼키려고 새로 만든 값이 아니라 "카운트 없음" 을 뜻하는
이 코드의 기존 단어다. `prompt_tokens` 를 문자열로 쓰는 게이트웨이 하나 때문에 답변 전체를 버리면
failover 한 번과 두 번째 제공자의 청구서와 호출자의 대기 시간을 지표 하나에 쓰는 셈이 된다. 그래서
`HttpSupport.tokenCount` 는 던지는 대신 `0` 으로 degrade 하고, 조용히가 아니라 로그를 남긴다 — Jackson
2 동작 중 남길 값이 있던 절반이 그것이고, 없던 절반이 침묵이다. `asInt` 는 여전히 숫자 문자열을
강제변환하므로 흔한 게이트웨이의 부주의는 아무 대가도 치르지 않고, 정말로 읽을 수 없는 카운트만 `0`
에 닿는다. `ProviderShapeDriftTest` 의 두 케이스가 양쪽을 고정한다.

`FAIL_ON_TRAILING_TOKENS` 와 `FAIL_ON_NULL_FOR_PRIMITIVES` 도 기본값이 뒤집혔다. 둘 다 뒤집힌 채로
두되 `Json` 에 이유를 적었다 — 모델이 JSON 뒤에 문장을 덧붙였다면 받은 스키마에 답한 것이 아니고,
절반만 읽는 것이 그걸 눈치채지 못한 방법이었다. `ChatController` 는 `ObjectMapper` 가 아니라
`JsonMapper` 를 주입받는다. Boot 4 의 XML·CBOR 구성이 각각 `ObjectMapper` 에 배정 가능한 빈을 하나씩 더
정의하므로, 둘 중 하나가 들어오는 날 넓은 타입은 `NoUniqueBeanDefinitionException` 이 된다.
`aimon-memory-engine` 은 import 하던 애너테이션 좌표를 전이 의존에 기대지 않고 직접 선언한다.

**Jackson 2 는 클래스패스에서 사라지지 않는다.** 이건 이 변경이 못 한 일이 아니라 할 수 없는 일이다.
아래 두 라이브러리를 제쳐두더라도, Jackson 3 의 databind 자체가 Jackson 2 의 애너테이션 아티팩트에
의존하므로 여기 어느 모듈도 그것에서 자유롭지 않다. springdoc 이 `swagger-core-jakarta` 를 거쳐
Jackson 2 를 끌어오고, `jjwt-jackson` 에는 Jackson 3 계열이 아예 없다. 둘 다 빈을 요구하지 않고 자기
매퍼를 직접 만들기 때문에 `spring-boot-jackson2` 를 되살릴 이유는 되지 않는다. `aimon-memory-client` 는
`aimon-core` 가 Jackson 2 를 runtime 으로 가져오므로 역시 둘 다 갖는다. `aimon-core` 는 공개 시그니처에
Jackson 2 를 노출한다 — `McpTransport.sendRequest(String, JsonNode)` 를 비롯해 스무 곳 남짓. 다만 그중
어느 것도 이 모듈이 구현하는 PeerMemory 계약(`at.aimon.core.memory.*`)에 있지 않고, 이 모듈이 import
하는 28개 타입에도 없다. 그것이 이 모듈을 옮길 수 있게 한 근거이고, "aimon-core 는 Jackson 을 노출하지
않는다" 보다 좁은 주장이다 — 넓은 쪽은 거짓이다.

나머지 여섯 모듈(llm·store·engine·embed·recall·testkit)의 런타임 클래스패스에는 이제
jackson-**databind** 2 가 없다. 다만 여섯 모듈 모두 `com.fasterxml.jackson.core:jackson-annotations` 는
여전히 갖는다. 바로 위 문단이 적었듯 Jackson 3 의 databind 가 그것에 의존하기 때문이다.

`checkAll` 과 `integrationTest` 가 모두 통과한다. `docs/openapi.json` 은 다시 한 바이트도 바뀌지 않았고,
`OpenApiSpecTest` 가 그것을 확인한다 — `spring-boot-jackson2` 를 뺀 것이 발행되는 스키마를 건드리지
않았다는 뜻이다.
