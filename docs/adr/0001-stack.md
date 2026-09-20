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
