**한국어** · [English](CHANGELOG.en.md)

# 변경 이력

이 파일의 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 를 따르고,
버전은 [유의적 버전](https://semver.org/lang/ko/)을 따른다.

**릴리스된 버전은 아직 없다.** Maven Central 에 올라간 아티팩트도 없다. 아래 `Unreleased` 는
`main` 의 커밋 히스토리에서 뽑은 것이고, 첫 릴리스가 나가는 시점에 그대로 첫 항목이 된다.

## [Unreleased]

### 추가

- 쌍(pair) 스코프 메모리 시스템 전체. 12개 Gradle 모듈, 하나의 코드베이스에서 도는 두 개의 실행
  프로세스(`aimon-memory-api`, `aimon-memory-worker`), 세 티어 읽기 — `context()`, `recall()`,
  `chat()`. 모든 사실은 방향이 있는 `(observer, observed)` 쌍에 속하고, 복합 외래키가 첫날부터
  그것을 강제한다.
- 여섯 신호를 고정 가중치로 융합하는 Tier 1 랭킹. 가중치 합은 항상 1.00 이라, 신호가 하나 빠지면
  분모가 줄어드는 대신 점수가 정직하게 내려간다. 모든 응답이 그 내역을 함께 싣는다.
- 골든 픽스처(소수 6자리), 라벨링된 랭킹 평가 세트(nDCG/MRR/recall), 정성 dialectic 세트 — 세 개의
  게이트를 서로 다른 이유로 실패하도록 분리해서 둔다.
- `aimon-memory-client` — aimon-core 의 `at.aimon.core.memory.PeerMemory` 다섯 티어를 이 서비스의
  HTTP API 위에 구현한 어댑터. Java 17 바이트코드로 컴파일되고, 이 빌드의 다른 모듈에 의존하지 않는다.
- 배포 전 게이트 `:aimon-memory-client:verifyCoreIsReleased`. aimon-core 가 릴리스 아티팩트가 아닌
  프로젝트나 스냅샷으로 풀렸거나, 풀린 jar 에 `PeerMemory` 가 없으면 publish 를 거부한다.
- aimon-core 의 다섯 티어 계약 스위트 21개를 `RemotePeerMemory` 에 걸었다. `RemotePeerMemoryWireTest`
  가 물을 수 없는 질문 — "두 백엔드가 같은 호출에 같은 뜻으로 답하는가" — 에 답하는 계층이다.
- `session_peer_windows` (V11). 멤버십을 덮어쓰지 않고 append-only 로 기록해서, 나갔다 돌아온 peer 가
  이전 구간을 잃지 않는다.
- ADR 0007 — aimon-core 와의 경계는 `PeerMemory` 뿐이라는 결정. 어느 쪽 빌드에도 드러나지 않지만
  양쪽에서 하중을 받는 계약이라 문서로 고정했다.
- 저장소 루트의 `LICENSE` (Apache-2.0). `gradle.properties` 의 POM 이 예전부터 주장해 온 파일이다.
- `NOTICE` — mem0(Apache-2.0)에서 온 랭킹 공식의 출발점에 대한 귀속. 근거는 ADR 0005.
- `docs/openapi.json` — 33개 라우트, 요청·응답 스키마, 오류 본문의 OpenAPI 3.1 서술. 실행 중인
  애플리케이션에서 생성해 커밋했고, 둘이 어긋나면 `OpenApiSpecTest` 가 실패한다. 라우트마다 필요한
  토큰 스코프는 `RoutePolicy` 에서 그때 읽어 찍기 때문에 인가 규칙의 사본이 하나 더 생기지 않는다.
  실행 중 서술 엔드포인트 `/v3/api-docs` 는 `AIMON_MEMORY_OPENAPI` 뒤에 있고 기본값은 꺼짐이다 —
  인증 인터셉터가 `/v1/**` 만 덮기 때문이고, actuator 를 별도 포트로 보낸 것과 같은 이유다. Swagger UI
  는 넣지 않았다. 그 webjar 는 Boot 의 정적 매핑이 플래그와 무관하게 서빙해 버린다.
- 거버넌스 문서 — `CONTRIBUTING`, `CODE_OF_CONDUCT`, `SECURITY`, 그리고 이 파일.
- `.github/` — 이슈 폼(버그·기능), PR 템플릿, dependabot, 태그 푸시로 도는 릴리스 워크플로.

### 변경

- 제품명이 `dyad` 에서 `aimon-memory` 로 바뀌었다. 패키지는 `dev.dyad.*` → `at.aimon.memory.*`,
  설정 키는 `dyad.*` → `aimon.memory.*`, 환경변수는 `DYAD_*` → `AIMON_MEMORY_*`, Micrometer 메트릭
  이름은 `dyad_queue_pending` → `aimon_memory_queue_pending`. 대시보드의 PromQL 도 함께 움직였다.
  모듈은 `modules/` 아래로 내려갔다.
- 빌드가 aimon-core 의 관례 위로 올라갔다. 사전 컴파일된 스크립트 플러그인(`aimon.java-conventions`,
  `aimon.publishable`), 버전 카탈로그 단일 출처, vanniktech maven-publish.
- `aimon-memory-client` 가 Central 의 `at.aimon.core:aimon-core:0.2.4` 로 빌드된다. 다섯 티어를 담은
  첫 릴리스라, 형제 체크아웃을 쓰던 composite build 가 사라졌다. CI 도 두 번째 저장소를 클론하지
  않는다.
- **세션 id 를 담은 `search` 요청을 거부한다.** 예전에는 세션을 조용히 무시하고 모든 세션의 결론을
  돌려줬다. 계약 스위트가 그 판단을 뒤집었다 — 돌지 않은 필터가 돈 것처럼 읽혀서는 안 된다.
  세션을 받는 것은 CHAT 티어다.
- 토큰 발급이 admin 전용에서 workspace 스코프로 바뀌었다. `TokenController` 가 발급자보다 넓은 것을
  거부하므로, 좁은 토큰을 나눠 주려고 모든 서비스에 admin 키를 두지 않아도 된다.
- 워크스페이스 튜닝 값을 **쓰는 시점에** 검증한다. 모르는 키, 합이 1.00 이 아닌 가중치, 범위를 벗어난
  숫자는 422 다. 읽기는 관대하게 두되 폴백을 로그로 남긴다 — 예전 행 하나가 다음 요청에서 워크스페이스의
  recall 을 통째로 죽이면 안 되기 때문이다.
- 명세 문서(`docs/spec/`) 안의 제품명도 함께 바뀌었다. `dyad-design.md`, `dyad-core` 처럼 아무것도
  가리키지 않게 된 이름 두 개가 있었다.
- 문서가 한국어 정본 + `.en.md` 영어 보조 체계로 바뀌었다.

### 수정

- **쌍 격리.** peer 토큰이 자기 워크스페이스의 임의의 쌍을 요청 본문이나 쿼리 문자열로 지정해 다른
  peer 의 비공개 결론을 읽을 수 있었다. `AuthInterceptor` 는 path 변수만 보기 때문에 확인할 방법이
  없었다. `PairScope` 가 모든 키를 만들고 observer 를 토큰과 대조한다. 컨트롤러가 `PairKey` 를 직접
  만들면 ArchUnit 규칙이 빌드를 깬다.
- ingest 본문의 발화자를 검증하지 않아, peer 토큰이 다른 사람 이름으로 메시지를 서명하고 그 사람에
  대한 결론으로 도출되게 만들 수 있었다.
- dialectic 의 메시지 도구 세 개가, chat 요청에 세션이 없으면 스코프 술어를 통째로 버리고 워크스페이스의
  모든 메시지를 모델에 넘겼다.
- `id` 하나가 요청 전부인 라우트(삭제, 감사 추적)는 이제 행에서 쌍을 읽어 대조하고, 권한 없음이 아니라
  찾을 수 없음으로 답한다. 결론 id 는 추측할 수 없으므로, "당신 것이 아님"과 "그런 행 없음"을 구분해
  주면 남의 행을 열거하는 오라클이 된다.
- SUMMARY 작업 단위를 아무도 큐에 넣지 않아 세션에 롤링 요약이 생기지 않았고, `context()` 는 늘 빈
  요약을 돌려줬다. DREAM 도 마찬가지였고, 부분 유니크 인덱스가 pending 을 진행 중으로 세기 때문에 그
  쌍은 다시는 dream 할 수 없고 수동 엔드포인트가 영원히 409 를 냈다.
- `extendClaim` 을 부르는 곳이 없어서, 5분 TTL 을 넘긴 작업 단위는 클레임이 회수된 채 두 번째 워커에
  다시 잡혔다 — 제공자 비용 두 배, 중복 제거에서 경합. 루프가 TTL 의 1/3 주기로 하트비트를 보낸다.
- 교차 peer 추출 프롬프트가 `%s` 세 개를 그대로 내보내고 네 번째 자리에 엉뚱한 이름을 넣었다.
  `.formatted` 가 `+` 보다 강하게 묶이기 때문이고, self 분기는 맞았기 때문에 단일 peer 테스트로는
  보이지 않았다.
- `join()` 이 매 ingest 마다 `observe_me` / `observe_others` 를 덮어써서, 아무도 관측하지 않도록
  설정한 세션이 다음 메시지에 워크스페이스 기본값으로 조용히 되돌아갔다 — 더 많이 기록하는 방향으로.
- 엔티티 링크 수를 쌍이 아니라 워크스페이스 전체에서 셌다. `ent` 신호가 존재 이유인 공유 엔티티에서
  정확히 무너졌고, 무관한 테넌트가 늘수록 더 나빠졌다.
- 스트리밍 폴백이 도구도 도구 결과도 없이 모델에 다시 물었다. 도구가 돌려주는 것 말고는 아무것도
  모른다고 적힌 프롬프트에 대고 — 모든 반복 비용을 지불한 뒤의 지어낸 답.
- 픽스처 키가 스트리밍 여부를 담지 않아 chat 과 stream 녹화가 충돌했다. 재생된 스트림은 miss 를 내지
  않고 빈 스트림을 돌려줬고, 한쪽을 녹화하면 다른 쪽이 지워졌다.
- Anthropic 백엔드가 지나간 API 를 향해 쓰여 있었다. 스키마를 시스템 프롬프트 뒤에 붙이고 어시스턴트
  턴을 여는 중괄호로 prefill 하는 방식이었는데, 4.6 이후 모델은 마지막 어시스턴트 턴 prefill 을 400 으로
  거부한다. 이제 `output_config.format` 을 선언한다.
- 보고되는 만료 시각이 12시간으로 하드코딩돼 있어, 수명을 설정하면 실제 `exp` 와 광고된 `exp` 가
  달라졌고 아무도 보지 않는 쪽만 맞았다.
- 벡터 컬럼이 1536 으로 하드코딩돼 있었다. 차원은 설정값이므로 이제 설정을 따르고, 어긋나면 두 숫자를
  모두 이름으로 밝히며 기동에 실패한다 — 모든 쓰기가 409 로 실패하는 대신.
- 없는 세션이 맨 `NoSuchElementException` 을 냈고 500 에 ERROR 로그로 보고됐다. 호출자의 오타와
  서버 장애를 구분할 수 없었다.
- `observe()` 가 호출자가 넘긴 `PeerView` 를 버리고 응답의 peer id 로 다시 만들었다. `PeerView` 의
  동등성은 `Principal` 전체를 보고 `Principal` 에는 표시 이름이 들어가므로, 방금 건네받은 subject 와
  같지 않은 subject 를 담은 관측이 돌아왔다. 계약 스위트가 잡아낸 결함이고, 어댑터는 올바른 바이트를
  보내고 올바른 바이트를 파싱하면서도 다른 뜻이 될 수 있다는 것이 이 계층을 건 이유다.
- **새로 클론한 저장소가 빌드되지 않던 문제.** `aimonCore` 가 Central 에 없는 `0.3.0-SNAPSHOT` 에
  고정돼 있어서, 스냅샷을 직접 publish 한 기계 밖에서는 `:aimon-memory-client:compileJava` 가 실패했다.
  aimon-core 는 릴리스된 0.2.4 로 돌아갔고, Central 에 없는 계약 스위트는 스스로 건너뛰는 별도
  소스셋으로 분리했다. 그 소스셋이 조용히 비는 두 경로 — 관대한 해석이 testkit 말고 다른 실패까지
  삼키는 것, 그리고 발견된 테스트가 0개여도 통과하는 것 — 은 각각 `verifyContractTestClasspath` 와
  `verifyContractTestRan` 이 막는다. CI 에는 testkit 이 없어 이 계층이 늘 건너뛰므로, 이 계층이
  비었다는 것을 밖에서 알아차릴 방법이 없다.

### 보안

- JWT 서명 키에 기본값이 없다. 비어 있거나 32바이트 미만이면 기동에 실패한다. `application.yml` 의
  개발용 기본값은 저장소에 공개된 서명 키이고, 그것으로 뜬 배포는 정상 배포와 구분되지 않는다.
- 토큰 수명 상한 30일. 폐기 목록이 없으므로 만료가 유출된 토큰을 끝내는 유일한 수단이다. 설정된
  기본 수명은 발급 시점이 아니라 **기동 시점에** 검사한다.
- `RoutePolicy` 라우트 허용목록과 `RoutePolicyCoverageTest`. 표에 없는 라우트는 거부되고, 살아 있는
  핸들러 매핑에 표에 없는 라우트가 있으면 빌드가 깨진다.

[Unreleased]: https://github.com/kangwoo/aimon-memory/commits/main
