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
