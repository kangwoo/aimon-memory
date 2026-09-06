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
- **문서 사이트** <https://kangwoo.github.io/aimon-memory/> — MkDocs Material 에 한국어·영어 전환과
  검색을 붙였고, `main` 에 들어간 것이 GitHub Pages 로 그대로 나간다. 빌드는 `--strict` 라
  깨진 문서 간 링크가 배포가 아니라 CI 에서 걸린다. 앵커까지 검사하도록 `validation.links.anchors`
  를 켜 뒀다 — MkDocs 의 기본값은 INFO 라서 `--strict` 로도 안 걸린다.
- ADR 0008 — 아키텍처 서술을 arc42 문서 하나(`docs/architecture.md`)로 모으고, 문서마다 소유하는
  주제를 정한 결정. README 에 흩어져 있던 모듈 그래프·설계 노트·관문 목록이 그리로 갔다.
- `docs/adr/` 과 `docs/spec/` 은 사이트에 올리지 않는다. 저장소에는 그대로 있고, 두 곳을 가리키는
  링크 80개는 `scripts/mkdocs_github_links.py` 가 렌더링 시점에 GitHub URL 로 바꾼다.

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
- 버전 카탈로그에서 아무도 쓰지 않던 항목 넷 — `jooq`, `pgvector`, `nanojson`, Testcontainers BOM —
  을 지웠다. 앞의 둘은 ADR 0002 가 결정하기 전의 의존성 초안에서 왔다. 파일 첫머리의 규칙을 이제
  양방향으로 적어 뒀다. 여기 없으면 안 쓰는 것이고, 아무도 안 쓰면 여기 있어서도 안 된다.
- **발행되는 POM 이 해석된 버전을 담는다.** Spring 의 dependency-management 가 주는 것에는 어느
  모듈도 버전을 선언하지 않는데 — 그게 그걸 쓰는 이유다 — 그 바람에 생성된 POM 이 버전 없는
  의존성을 싣고 나갔다. `aimon-memory-store` 는 11개 중 7개가 그랬다. Gradle 소비자는 모듈
  메타데이터로 버티지만 Maven 소비자는 POM 을 읽고 해석에 실패한다. `versionMapping` 이 빌드가
  실제로 해석한 값을 적는다. 아홉 좌표 전부에서 버전 없는 의존성이 0개가 됐다.
- **저장소 SPI 를 봉인했다.** `ConclusionStore`(5→16개 메서드)와 `EntityStore`(2→11개)가 넓어졌고,
  recall·engine·api·worker 가 `ConclusionRepository` 같은 구상 클래스 대신 이 타입들을 주입받는다.
  그전까지 "저장소를 갈아 끼울 수 있다"는 주장은 참이 아니었다 — 인터페이스는 있었지만 부르는 곳이
  없었다. 규칙은 한 줄이다. `aimon-memory-store` 밖에서 호출되는 메서드가 SPI 에 있다. 나머지 아홉
  리포지토리는 아직 구상 타입이고, `SpiSurfaceTest` 가 그 아홉을 이름으로 적어 둔다 — 할 일 목록이
  아니라 재고 목록이다. `EventLog` 는 그대로다.
- **모듈 배치와 조립 그래프를 정리했다.** `HashingEmbedder` 가 `engine` 에서 `embed` 로 옮겨 갔고,
  `MemoryConfiguration` 이 쪼개졌다 — `RecallConfiguration` 이 새로 생기고, `AnalyzerRegistry` 와
  `TextProperties` 는 store 로, `Embedder` 와 `EmbedProperties` 는 embed 로 갔다. 그 결과 `recall` 이
  `engine` 없이 조립된다. 의존도 함께 좁혔다 — `api` 에서 `text` 를, `worker` 에서 `llm` 과 `embed` 를
  뺐고, `store` 의 postgresql 은 `api` 에서 `implementation` 으로 내렸다.
- **발행 모듈의 공개 타입 셋이 옮겨지거나 좁아졌다.** `Bm25.CorpusStats` 는
  `at.aimon.memory.core.model.CorpusStats` 로, `EntityRepository.EntityLink` 는
  `at.aimon.memory.core.model.EntityLink` 로 나왔다 — 둘 다 SPI 가 주고받는 타입인데 SPI 보다 위에
  있는 클래스 안에 중첩돼 있어서, `core.spi` 가 자기 시그니처에 쓸 수 없었다. `Jsonb.of` 는 반환
  타입이 `PGobject` 에서 `Object` 로 좁아졌다. 그게 `aimon-memory-store` 의 ABI 에서 드라이버 타입을
  이름 부르는 유일한 자리였고, 그래서 postgresql 이 `api` 여야 했으며, 그래서 recall·engine·api·worker
  의 컴파일 클래스패스에 드라이버가 올라와 있었다. **소비자는 없다** — 릴리스도 태그도 아직 0건이고
  버전은 `0.1.0-SNAPSHOT` 이라, 셋 다 아무것도 깨뜨리지 않는다. 첫 릴리스 전에 옮겨 둔 이유가 그것이다.
- **모르는 provider 이름이 기동에서 실패한다.** `AIMON_MEMORY_LLM_PROVIDER=openal` 은 예전에 `none`
  으로 취급돼서, 배포가 멀쩡히 뜬 다음 모든 모델 호출에 `llm_not_configured` 로 답했다. 이제
  `unknown_llm_provider` 다. `none` 과 빈 문자열만 "끄기"로 인정된다. 임베더도 같다
  (`unknown_embed_provider`).
- `?tokens` 에 상한 128000 이 생겼다(`Bounds.MAX_CONTEXT_TOKENS`). Tier 0 의 응답을 묶는 것은 예산뿐이라
  상한 없는 예산이 상한 없는 응답이었다 — 이 저장소의 다른 페이징 파라미터는 모두 `Bounds` 를 지난다.
- **계약 스위트를 Central 의 스냅샷 저장소에서 푼다.** `at.aimon.core:aimon-memory-testkit` 은
  릴리스가 없지만(0.3.0 이 처음) 스냅샷은 발행돼 있고, 빌드가 그 좌표 하나만 거기서 풀도록 열어
  뒀다. `mavenLocal()` 을 대신한 것이고, 차이가 요점이다 — 로컬 발행은 기계 한 대에서만 풀리므로
  계약 계층이 거기서만 돌고 CI 를 포함한 나머지 전부에서 건너뛰었다. 이제 새 클론에서도 CI 에서도
  21개가 돈다. 근거는 ADR 0007 의 세 번째 덧붙임.
- **메시지 배치가 행마다 INSERT 를 내는 대신 한 문장으로 나간다.** `MessageRepository.insertBatch` 는
  배치 안의 행마다 `INSERT … RETURNING` 을 하나씩 냈다. 폭발하지는 않는다 — 배치는 `@Size(max = 100)`
  으로 묶여 있다 — 하지만 100건이면 한 트랜잭션 안에서 왕복 100번, 파싱 100번이다. 이제 다중 VALUES
  한 문장이다. 루프가 불가피해 보였던 이유는 `RETURNING` 이다. `id` 와 `created_at` 은 생성 컬럼이고
  호출자가 둘 다 필요한데 JDBC 배치 실행은 결과 집합을 돌려주지 않는다. Postgres 는 돌려준다 —
  다중 행 `INSERT … RETURNING` 은 한 문장이면서 모든 행을 돌려준다. **동작은 바뀌지 않는다:** 같은 행,
  같은 순서, 같은 컬럼. 행당 파라미터 7개에 프로토콜 한계 65535 이므로 천장은 정확히 9362행이고,
  9363행은 문장이 JVM 을 떠나기 전에 드라이버가 거절한다 — `MessageIngestionService.MAX_BATCH` 의
  두 자릿수 위이고, 언젠가 그 수가 바뀌더라도 조용히가 아니라 시끄럽게 깨진다. **측정치**(로컬 Testcontainers pgvector,
  100건 배치, 워밍업 3회 뒤 15라운드를 한 라운드 안에서 번갈아): 세 번 돌린 결과 행별 루프 중앙값은
  15.0~16.3 ms, 한 문장은 2.3~2.9 ms 였다. 같은 기계에서 5배에서 7배 사이다.
  **이 숫자는 loopback 을 설명하지 배포를 설명하지 않는다.** 왕복이 100번에서 1번이 되는 변화라
  네트워크 지연이 있는 곳에서는 차이가 더 커지지만, 그건 측정한 바 없다. 회귀를 막는 것은 시간이 아니라
  `MessageBatchInsertTest` 가 세는 **문장 수**다.

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
- **수집 요청의 메시지 제약이 한 번도 검사되지 않았다. 이제 검사되고, 그만큼 동작이 달라진다.**
  `CreateMessages.messages` 에 `@Valid` 가 없어서 Bean Validation 이 리스트에서 멈추고 원소로 내려가지
  않았다. `NewMessage` 가 선언해 둔 `@NotBlank` 는 전부 죽은 글자였고, **내용이 비었거나 공백뿐인
  메시지가 200 으로 통과해 저장됐다.** 이제 **400** 이다. 거부되는 것처럼 보이던 빈 `peer` 는 실은 훨씬
  아래 키 인코더가 `bad_key` 로 막고 있던 것이라, 이 층이 비어 있다는 사실이 가려져 있었다.
  **소비자에게 보이는 변경이다** — 빈 content 를 보내고 200 을 받던 클라이언트는 이제 400 을 받는다.
  DTO 가 이미 선언한 계약을 되살리는 쪽을 택했다. 저장된 빈 메시지는 어차피 도출도 recall 도 되지
  않으면서 자리만 차지했다.
- **세션 참여자 추가에서도 같은 결함을 걷어냈다.** `AddSessionPeers.peers` 에 `@Valid` 가 없어서
  `SessionPeerSpec.peer` 의 `@NotBlank` 가 죽어 있었다. `{"peers":[{"peer":"   "}]}` 가 **200 으로
  통과했고, 이름이 공백뿐인 peer 행이 실제로 만들어져 세션에 join 됐다.** 이제 400 `bad_request` 다.
  `peer` 가 `null` 인 경우만 예외적으로 409 `constraint_violation` 으로 막히고 있었는데, 그것도 이제
  400 으로 온다. **소비자에게 보이는 변경이다.**
- **dialectic 의 대화 이력도 마찬가지였다.** `ChatRequest.history` 에 `@Valid` 가 없어서 `ChatTurn` 의
  `@NotBlank` 두 개가 평가되지 않았고, `content` 나 `role` 이 빈 turn 이 **그대로 모델 제공자에게
  전송됐다.** `POST /chat` 과 `/chat/stream` 둘 다 이제 400 이다. **소비자에게 보이는 변경이다.**
- **빈 엔티티 이름이 이름 없는 노드 하나를 만들어 `ent` 신호를 오염시켰다.** `""`, `"   "`, `"\t"` 가
  전부 같은 빈 키로 정규화되므로 쓰레기 여러 개가 아니라 **이름이 없는 노드 하나**가 생기고, 빈
  문자열을 달고 온 모든 결론이 거기 연결됐다. 그 노드는 링크가 있어 orphan 청소가 지우지 않고, 벡터가
  있어 인덱스에서 `entityTopK` 슬롯을 차지한다. 그리고 그 링크들이 서로 아무 관계 없는 결론을 향하므로,
  질의 벡터가 그 근처에 떨어지면 **무관한 결론들이 `ent`(가중치 0.13)를 한꺼번에 받는다.**
  `GET /recall/provenance?entity=` 에 공백을 줘도 200 으로 그 노드가 돌아왔다.
- **그 구조는 임베더와 무관하지만, 거기 붙인 수치는 그렇지 않다.** 대역 임베더에서 무관한 사실 셋이 각각
  `ent = 0.996` 으로 나왔고 — 이건 링크 3개에 대한 `countWeight` 값 그 이상이 아니다 — explain 은
  `matchedEntities: [""]` 로 보고했다. 다만 그 수치를 보려면 맞는 질의가 필요했다. `StubEmbedder` 는
  토큰을 못 찾은 텍스트에 `"empty"` 토큰으로 폴백하므로 빈 노드는 그 단어의 벡터를 갖고, 다른 질의에서는
  `ent = 0.0` 이었다. 실제 임베더가 `embed("")` 를 실제 질의들 사이 어디에 놓는지는 **측정한 바 없고**,
  따라서 그 노드가 얼마나 자주 걸리는지도 모른다. 임베더와 무관하게 남는 것은 구조다 — 노드 하나,
  링크가 있어 orphan 청소를 살아남고, `entityTopK` 슬롯을 차지하며, 걸릴 때마다 거기 달린 것을 한꺼번에
  들어 올린다.
- **그래서 두 계층으로 고쳤고, 두 계층이 서로 다른 답을 하는 것이 요점이다.**
  - `EntityPipeline.linkAll` 에서 **걸러 낸다(거절이 아니다).** 이 시스템에 들어오는 엔티티 이름은
    주입 엔드포인트·deriver·dreamer 셋이 전부이고 모두 이 길목을 지난다. 거절하지 않는 이유는 여기 오는
    것 대부분이 **모델 출력**이기 때문이고, 예외를 던져 봐야 쓰기가 되돌아가지도 않는다.
    `ConclusionWriter.write` 는 트랜잭션이 아니라서 배치의 결론은 이미 커밋돼 있고 **엔티티 엣지만 빠진
    채** 남는다. 그 상태로 work unit 이 5회 재시도하며 같은 사실을 다시 도출하고, dedup 이 그것을 강화로
    세어 `times_derived`(= `reinf` 신호)를 부풀린 뒤 배치가 격리된다. `DeriverService` 와
    `DreamerService` 가 **content** 가 빈 항목을 이미 건너뛰고 있고, 이건 한 필드 옆에서 같은 판단을 한
    것이다.
  - `POST /v1/workspaces/{ws}/conclusions` 는 **거절한다.** `CreateConclusion.entities` 가
    `List<@NotBlank @UsableName String>` 이 되어 400 이다. HTTP 클라이언트는 자기 버그를 고칠 수 있으니
    조용히 버리는 것보다 알려 주는 편이 낫다. 덤으로 `"entities": [null]` 이 **500 `internal_error`** 를
    내던 것도 함께 닫혔다 — 이제 400 이다.
  - 두 계층이 blank 의 정의부터 맞아야 했다. `@NotBlank` 는 `String.trim()`(U+0020 이하)으로 규정돼 있고
    필터는 `String.isBlank()`(`Character.isWhitespace`)를 쓴다. 그래서 `entities: ["\u2000"]` 은
    **200 으로 받아들여진 뒤 흔적 없이 버려졌다** — 400 이 막으려던 바로 그 조용한 실종이 제약 자신을
    통해 일어난 것이다. `@UsableName` 이 그 규칙이고, 필터와 같은 메서드를 쓴다.
- **이미 저장된 노드는 지우고, 불변식을 스키마로 내렸다 (V12).** 필터는 새로 들어오는 것만 막는다.
  이미 있는 이름 없는 노드는 링크가 있어 orphan 청소가 남기고, `reindex` 가 빈 표시 이름을 다시 임베딩해
  인덱스에 돌려놓는다. `V12` 가 그것들을 지우고(`entity_links` 는 cascade), `ck_entity_name_norm
  CHECK (name_norm <> '')` 를 추가한다. 이 검사는 모델 출력으로는 걸릴 수 없다 — `isUsable` 은
  `String.isBlank()`, `normalize` 는 `String.strip()` 으로 같은 술어이기 때문이다. 즉 이게 걸리면 이미
  데이터베이스 위쪽 코드가 틀린 것이고, `ck_level`·`ck_sync_state`·`ck_explicit_needs_session` 이
  하는 일이 정확히 그것이다.
- 위 `entities` 제약은 앞선 셋과 **성격이 다르다.** 그쪽은 발행 스키마가 이미 약속하고 있던 계약을
  런타임이 지키게 만든 것이고, 이건 **없던 제약을 새로 만든 것**이다. 그래서 이번에는
  `docs/openapi.json` 이 한 줄 바뀐다 — `CreateConclusion.entities.items` 에 `minLength: 1`.
- 위 둘과 `CreateMessages` 까지 **세 자리가 같은 결함이었다** — 리스트를 받는 필드에 `@Valid` 가 없으면
  Bean Validation 이 리스트에서 멈추고 원소로 내려가지 않는다. 원소 타입의 제약은 선언만 되어 있고 한
  번도 평가되지 않는다. 이 결함이 오래 보이지 않은 이유가 특히 짚어 둘 값어치가 있다. **발행된 스키마는
  내내 옳았다.** springdoc 은 참조되는 타입의 애노테이션을 읽으므로 `docs/openapi.json` 은 처음부터
  `ChatTurn.role`·`ChatTurn.content`·`SessionPeerSpec.peer` 에 `minLength: 1` 을 싣고 있었다. 즉 문서는
  강제한다고 말하고 있었고 런타임에는 그 강제가 없었다. **그래서 이번 수정으로 `docs/openapi.json` 은
  한 줄도 바뀌지 않았다** — 구현이 문서를 따라잡은 것이지 그 반대가 아니다.
- 한 메시지의 길이에 상한이 생겼다 — `Requests.MAX_CONTENT_CHARS`, 32000자. 배치는 오래전부터 100건으로
  묶여 있었는데 메시지 자체는 아니어서, 요청 크기도 `GET /context` 의 **응답** 크기도 가장 큰 메시지가
  정하고 있었다. 32000자는 4자/토큰 근사로 8191 토큰이고, `OpenAiEmbedder` 가 입력을 자르는 지점이다 —
  그보다 긴 텍스트는 벡터가 되기 전에 잘리므로, 저장해 두면 시맨틱 recall 이 영영 볼 수 없는 꼬리를
  저장하는 셈이 된다. 아무 말도 없이 그러느니 거부한다. `docs/openapi.json` 에 `maxLength: 32000` 으로
  실려 있다.
- **세션 참여자 명단에 크기 상한이 생겼다 — 한 요청에 100명, 넘으면 400.** `Requests.MAX_SESSION_PEERS`.
  `AddSessionPeers.peers` 는 `@NotEmpty` 뿐이라 명단이 무제한이었고, 요청 하나가 임의 개수의 쓰기를
  만들었다 — `HierarchyController` 가 원소마다 `peers.getOrCreate`(insert + read)와
  `sessionPeers.join`(upsert + window insert)을 부르므로 **원소당 네 문장**이다. 그리고 늘어난 명단은
  그 세션에 들어오는 **모든** 메시지 배치의 fan-out 을 넓힌다. ADR 0006 이 그 비용을 N + N(N−1)
  추출 호출로 못박아 뒀다. 100 은 바로 옆 `CreateMessages.messages` 가 처음부터 쓰던 수이고
  (`MessageIngestionService.MAX_BATCH` 가 한 층 아래에서 같은 수를 강제한다), 문서가 다루는 가장 큰 방의
  두 배다 — 가이드의 예시가 10명 방(배치당 100회)이고 ADR 0006 이 `observe_others` 를 꺼야 한다고
  지목하는 것이 50명 채널이다. 그 여유가 요점이다. 이 상한은 fan-out 을 묶지 않고, 묶을 수도 없다 —
  아래를 보라.
  **소비자에게 보이는 변경이다** — 101명짜리 명단을 보내고 200 을 받던 클라이언트는 이제 400 이다.
  잘라 주지 않는 이유가 이 상한의 요점이다. `Bounds` 가 페이징·limit 을 clamp 하는 근거는 "더 남았는지는
  페이지네이션이 말해 준다"인데 명단에는 그런 신호가 없고, `PUT` 에서는 잘라 내는 것이 **파괴적**이다 —
  `SessionPeerRepository.replace` 는 명단에 없는 사람의 멤버십을 닫으므로, 조용히 버려진 101번째 이후의
  peer 들은 안 들어가는 게 아니라 **세션에서 빠지고** 그 세션의 메시지를 읽을 권한을 잃는다.
  이건 발행 스키마가 아무 말도 하지 않던 자리에 **없던 제약을 새로 만든 것**이라 —
  `CreateConclusion.entities` 와 같은 성격이다 — `docs/openapi.json` 이 한 줄 바뀐다.
  `AddSessionPeers.peers` 에 `maxItems: 100`. `@Size(min = 1, ...)` 로 쓴 것은 springdoc 이 `@Size` 를
  찾으면 거기서 `minItems` 를 뽑기 때문이다. `max` 만 적었으면 이미 발행돼 있던 `minItems: 1` 이 `0`
  으로 바뀌었을 것이다. `NewMessage` 가 한 필드 옆에서 적어 둔 것과 같은 함정이다.
  **한 요청을 묶는 것이지 명단 자체를 묶는 것이 아니다.** `POST` 는 더하는 것이라 100명씩 나눠 부르면
  그보다 큰 방을 만들 수 있다. 그건 검증이 아니라 rate limit 의 몫이라 남겨 뒀다.
- **Tier 1 recall 의 후보 집합에 천장이 생겼다 — 신호 경로당 1000행. 거절이 아니라 clamp 다.**
  한 번의 recall 이 부담하는 비용은 `limit × oversample` 인데, 두 인자는 각각 100 으로 묶여 있고
  **곱은 아무도 안 보고 있었다.** `RecallService` 는 그 곱을 `conclusions.semantic` 과
  `conclusions.keyword` 에 그대로 넘기므로 경로당 10000행, 합쳐 20000개의 `Conclusion` 이
  `LinkedHashMap` 에 쌓이고, 거기서 다시 `id = ANY (?)` 배열 하나, Java BM25 한 바퀴, `linksAmong` 배열
  하나, 정렬 한 번을 지나 최대 100개를 돌려준다. `Bounds` 의 javadoc 이 "랭커가 신호 경로마다 그
  배수만큼 oversample 하기 때문에 recall 은 선형보다 나쁘다"고 적어 둔 그 배수가, 정작 곱에서는 열려
  있었다. 1000 은 이 시스템이 recall 경로 하나에 **이미** 허용하고 있는 폭이다 —
  `recall.entity_top_k` 가 같은 `parse()` 안에서 `[1, 1000]` 으로 검증된다.
  **이전에 통과하던 요청이나 설정은 하나도 거절되지 않는다.** `recall.oversample` 의 범위는 `[1, 100]`
  그대로이고, `oversample: 100` 은 기본 `limit` 10 에서 정확히 1000 개를 가져와 온전히 존중된다.
  기본값 10 × 4 = 40 은 천장의 25분의 1이다. 설정 값을 422 로 거절하지 않은 이유는 그 값이 **honour 될
  수 있기** 때문이다 — `bounded()` 는 배수를 검증할 뿐 그것이 곱해질 `limit` 을 볼 수 없으므로, 곱의
  천장은 쓰기 경계에서 표현할 수 있는 것이 아니다. clamp 는 응답에 실릴 수 없다 —
  `candidatesConsidered` 는 신호 경로들이 실제로 만들어 낸 합집합의 크기이지 요청한 폭이 아니다 —
  그래서 `RecallService` 가 `debug` 로 로그를 남긴다. 순위가 달라졌는데 어느 로그에도 이유가 없는 것은
  `WorkspaceSettingsService` 가 한 모듈 옆에서 이미 이름 붙여 둔 실패다. 거기서는 쓸 수 없는 설정이
  기본값으로 떨어지면서 "그러지 않으면 증상이 튜닝이 안 먹는다는 것뿐이라" 로그를 남긴다.
  **바뀌는 것은 결과다.** 곱이 1000 을 넘던 자리에서는 두 경로가 보는 후보 집합이 좁아지므로, 천장
  위로 튜닝해 둔 workspace 는 예전과 다른 순위를 받을 수 있다. 그게 이 상한의 대가이고, 이 변경이
  바꾸는 동작은 그것뿐이다.
- **새로 클론한 저장소가 빌드되지 않던 문제.** `aimonCore` 가 Central 에 없는 `0.3.0-SNAPSHOT` 에
  고정돼 있어서, 스냅샷을 직접 publish 한 기계 밖에서는 `:aimon-memory-client:compileJava` 가 실패했다.
  aimon-core 는 릴리스된 0.2.4 로 돌아갔고, Central 에 없는 계약 스위트는 스스로 건너뛰는 별도
  소스셋으로 분리했다. 그 소스셋이 조용히 비는 두 경로 — 관대한 해석이 testkit 말고 다른 실패까지
  삼키는 것, 그리고 발견된 테스트가 0개여도 통과하는 것 — 은 각각 `verifyContractTestClasspath` 와
  `verifyContractTestRan` 이 막는다. 그 뒤 testkit 이 Central 스냅샷으로 올라가면서 이 계층은 CI 를
  포함해 어디서나 돌게 됐다(위 `변경` 참고). 건너뛰기는 좌표가 풀리지 않는 경우를 위해 남아 있다.
- **아포스트로피와 천단위 콤마가 붙은 질의어가 키워드 후보를 하나도 못 만들던 문제.** Lucene 표준
  토크나이저는 `alice's` 와 `50,000` 을 토큰 하나로 내보내므로 이 둘은 `content_analyzed` 와 질의어
  양쪽에 똑같이 들어가는데, `TsQuery` 가 구두점을 깎아 `alices` · `50000` 으로 보냈다. 어느 쪽도 자기가
  분석돼 나온 문서와 매칭되지 않는다 — Postgres 16 에서 3행짜리 코퍼스로 재 보면 미제거형이 2행을
  찾는 자리에서 0행이다. 영어 소유격·축약형·자릿수 콤마가 붙은 모든 질의가 해당한다. 이제 둘 다 남긴다.
  콤마는 어느 위치에서도 연산자가 아니라 그냥 허용 문자로 넣었고, 아포스트로피는 **영숫자 사이에서만**
  남긴다 — 홑따옴표로 시작하는 항은 닫히지 않는 인용 어휘소가 되어 질의 전체가 구문 오류로 죽는다.
  워드 내부의 `:` 는 계속 제거한다. 그쪽은 위치로 구제되지 않는 진짜 연산자라서, `note:draft` 는
  여전히 자기 문서를 못 찾는다.

  **거절되던 요청이 통과하게 되는 변경은 없고, 오류 코드도 그대로다.** 바뀌는 것은 리트리벌 결과다:
  소유격이나 자릿수 콤마가 든 질의는 예전에 키워드 경로가 전혀 내놓지 못하던 행을 이제 후보로 올린다.
  같은 질의의 순위가 달라질 수 있다. 골든 픽스처와 랭킹 기준선은 움직이지 않았고 그럴 이유도 없다 —
  두 픽스처 모두 구두점을 전부 쪼개는 스텁 분석기로 색인되고, 코퍼스에도 이 세 문자가 없다.

### 보안

- JWT 서명 키에 기본값이 없다. 비어 있거나 32바이트 미만이면 기동에 실패한다. `application.yml` 의
  개발용 기본값은 저장소에 공개된 서명 키이고, 그것으로 뜬 배포는 정상 배포와 구분되지 않는다.
- 토큰 수명 상한 30일. 폐기 목록이 없으므로 만료가 유출된 토큰을 끝내는 유일한 수단이다. 설정된
  기본 수명은 발급 시점이 아니라 **기동 시점에** 검사한다.
- `RoutePolicy` 라우트 허용목록과 `RoutePolicyCoverageTest`. 표에 없는 라우트는 거부되고, 살아 있는
  핸들러 매핑에 표에 없는 라우트가 있으면 빌드가 깨진다.
- `DatabaseCredentialCheck` — 저장소에 커밋된 기본 비밀번호를 이 기계가 아닌 데이터베이스에 대고 쓰면
  기동을 거부한다(`default_db_password`). 그 값은 저장소를 읽은 누구나 안다. 로컬 흐름
  (`docker compose` · Testcontainers)은 루프백이라 그대로 통과한다.
- **5xx 본문은 호출자가 보낸 적 없는 값을 더 이상 싣지 않는다.** `MemoryException` 의 메시지가 응답
  본문이 되는 설계는 그대로다 — `store_failed` 가 workspace·entity·session 이름을 실어도 그건 호출자가
  방금 보낸 값이라 새로 나가는 것이 없다. 바뀐 것은 **호출자가 보낸 적 없는** 값을 싣던 다섯 자리다.
  `Jsonb` 는 파싱되지 않는 jsonb 컬럼을 통째로 실었고(`metadata` · `configuration`, 그리고 어떤 응답
  DTO 에도 없는 `internal_metadata` 까지), `HttpSupport` 와 `OpenAiEmbedder` 는 제공자의 오류 본문을
  실었으며(OpenAI 의 401 본문은 설정된 API 키를 가운데만 가린 채 되돌려준다), `Json` 은 모델의 출력
  200자를 실었고(모델 출력은 저장된 결론·메시지로 지은 프롬프트에서 나온다), 두 백엔드의 스트림 오류는
  제공자의 자유 문장을, `KoreanTextAnalyzer` 는 서버의 절대 경로를 실었다. 전부 로그로 옮겼다 — **잘라
  내지 않은 전체**를, 진단에 필요한 자리에. 본문에는 상태 코드·오류 종류처럼 호출자가 행동에 옮길 수
  있는 것만 남는다.
- **`fixture_miss` 는 프롬프트를 더 이상 돌려주지 않는다 — 503 으로도, 200 으로도.**
  `FixtureMissException` 은 테스트 하네스용 진단이다. fixture 디렉터리를 절대 경로로 말하고,
  canonical request 전체 — 시스템 프롬프트, 도구 스키마, 모든 turn — 를 그대로 싣는다. 그것이 API
  표면이 되는 이유는 replay 가 옵트인이 아니기 때문이다. `LlmMode.fromEnvironment` 는
  `AIMON_MEMORY_LLM_MODE` 도 `aimon.memory.llm.mode` 도 없으면 `REPLAY` 를 돌려주고,
  `MemoryConfiguration.llmClient` 는 설정된 제공자를 조건 없이 `RecordingChatBackend` 로 감싼다.
  그래서 제공자와 키만 설정하고 모드를 두지 않은 배포는 모든 모델 호출에 그 본문을 돌려주고 있었다.
  예외의 메시지는 그대로 둔다 — 그것이 쓰이는 자리는 실패한 `./gradlew test` 다. 대신 그 분리를
  `MemoryException.publicMessage` 에 둔다. 응답으로 메시지를 옮겨 싣는 자리가 `ApiExceptionHandler`
  하나가 아니기 때문이다. 실패한 dream 은 예외 메시지를 `dreams.error` 에 저장하고
  `Dtos.DreamResponse` 가 그 컬럼을 **200** 으로 돌려준다 — 5xx 경계에서만 막으면 같은 값이 그리로
  나간다. 두 자리 모두 이제 `publicMessage` 를 쓴다.
- **200 으로 나가는 두 자리도 이제 호출자에게 쓴 문장만 싣는다.** `MemoryException.publicMessageOf` 는
  `MemoryException` 이 아닌 예외에는 그 예외의 메시지를 그대로 돌려주고 있었다 — 즉 규칙이 걸린 것은
  `catch (RuntimeException)` 이 잡는 것들 중 **이 빌드가 지은 절반뿐**이었다. 나머지 절반은 드라이버·
  라이브러리·JDK 가 로그를 읽을 사람에게 쓴 문장이다. Postgres 오류 하나가 실행된 문장 전체와 제약·
  릴레이션 이름을 담고, not-null·check 위반이면 `Detail: Failing row contains (…)` 로 실패한 행 자체를
  덧붙인다. 실측: dream 이 not-null 컬럼에 걸린 배포에서 `GET /v1/workspaces/{ws}/dreams` 가 **200**
  으로 1,462바이트를 돌려줬고, 그 안에 INSERT 문 전체, 모든 컬럼 이름, `ON CONFLICT` 의 중복 제거 범위,
  그리고 dream 이 방금 도출한 결론의 원문·정규화형·해시·벡터 앞부분이 들어 있었다. 같은 예외가 HTTP
  경로로 나가면 `ApiExceptionHandler.constraint` 가 124바이트로 거절한다 — 한쪽 문으로 막은 것이 다른
  쪽 문으로 나가고 있었다. 이제 `MemoryException` 이 아닌 것은 전부 `an internal failure; see the
  server log` 한 줄이 된다. 같은 규칙이 `GET /v1/workspaces/{ws}/conclusions/{id}/events` 가 200 으로
  돌려주는 `sync_error` 에도 걸린다(임베딩 백필의 UPDATE 가 실패하면 문장과 스키마가 실렸다). `dreams.error`
  를 응답에서 빼지 않은 이유는, dream 은 워커에서 실패해서 5xx 도 `code` 도 없고 그 문자열이 통로의
  전부이기 때문이다 — `llm_not_configured` 처럼 호출자가 고칠 수 있는 실패와 서버 고장을 구분할 방법이
  사라진다.
- **도구 실패 문자열에도 같은 규칙이 걸린다.** `DefaultLlmClient` 는 실패한 도구를 모델에게 결과로
  돌려주고, 모델의 답변은 `POST /chat` 의 200 본문이 된다 — 메시지가 호출자에게 닿는 가장 먼 경로다.
  도구는 recall 과 search 라 저장소를 건드리고, 거기서 나온 `DataAccessException` 이 그대로 모델에게
  가고 있었다. 이제 `MemoryException` 만 그대로 가고 나머지는 요약된다. 그래서 `ToolRegistry` 가 모델의
  잘못된 도구 인자에 던지던 예외는 `IllegalArgumentException` 에서 `MemoryException`
  (`bad_tool_arguments`) 이 됐다 — 그 문장은 모델더러 인자를 고쳐 다시 부르라고 쓴 것이고, 타입이
  그 사실을 말하는 자리다.
- **로그는 오히려 늘었다.** `ReconcilerService` 의 임베딩 실패 로그는 이제 예외 자체(스택 포함)를 받고,
  `DefaultLlmClient` 는 실패한 도구 호출을 로그로 남긴다 — 예전에는 아무 데도 남지 않았고 요약만
  모델에게 갔다. `queue.last_error` 는 그대로 전체 메시지를 담는다. 라우트가 읽지 않는 컬럼이고
  (`QueueRepository.QueueItem` 에 오류 필드가 없다), 운영자의 runbook SQL 이 유일한 독자다.
- `Jsonb` 자리는 **요청으로 도달할 수 없다.** 이 컬럼들에 쓰는 경로는 전부 `Map<String, Object>` 나
  `List<String>` 로 타입이 잡혀 있고 Postgres 가 jsonb 를 입력에서 검증하므로, ingress 를 통과한 본문은
  읽기도 통과한다. 이 빌드가 쓰지 않은 바이트(운영자의 UPDATE, 복구, 손으로 쓴 마이그레이션)에서만
  터진다. 그래도 고친 이유는 그때가 바로 그 500 을 봐서는 안 될 사람이 보게 되는 때이기 때문이다.
  나머지 넷은 제공자가 오류를 돌려주는 순간 평범한 요청으로 도달한다.

[Unreleased]: https://github.com/kangwoo/aimon-memory/commits/main
