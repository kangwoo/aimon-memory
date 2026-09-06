**한국어** · [English](CONTRIBUTING.en.md)

# 기여하기

읽고 고치고 보내면 된다. 아래는 이 저장소가 실제로 어떻게 돌아가는지에 대한 설명이지,
지켜야 할 예절 목록이 아니다.

---

## 준비물

- **JDK 21.** `gradle/libs.versions.toml` 의 `java` 가 유일한 출처다. Gradle 은 wrapper 로 따라오므로
  `./gradlew` 말고 따로 설치할 것은 없다.
- **Docker.** `docker compose` 로 로컬 Postgres 를 띄우는 데 쓰고, Testcontainers 계층이 자기 데이터베이스를
  띄우는 데 쓴다. 빠른 관문(`checkAll`)은 Docker 없이 돈다.

의존성은 전부 Maven Central 에서 온다. `at.aimon.core:aimon-core:0.2.4` 도 마찬가지라, 클론한 다음
바로 빌드된다. 좌표 하나만 릴리스가 아니라 Central 의 스냅샷 저장소에서 오는데, 그것도 원격이라
따로 준비할 것은 없다. 사정은 아래 "계약 계층"에 적어 뒀다.

```sh
git clone https://github.com/kangwoo/aimon-memory
cd aimon-memory
docker compose up -d
./gradlew checkAll
```

---

## 관문

| 명령 | 무엇을 보는가 | Docker | 언제 돌리나 |
|---|---|:-:|---|
| `./gradlew checkAll` | 포맷·스타일·BOM, 데이터베이스가 필요 없는 259개 테스트, 그리고 계약 21개 | 불필요 | 저장할 때마다 |
| `./gradlew integrationTest` | Testcontainers 계층 278개 | 필요 | PR 을 올리기 전에 |
| `./gradlew :aimon-memory-client:contractTest` | aimon-core 의 `PeerMemory` 계약 21개 | 불필요 | `checkAll` 이 이미 부른다. 아래 참조 |
| `./gradlew :aimon-memory-worker:loadTest` | 경합 상태의 동시 읽기·쓰기 | 필요 | 워커나 큐를 건드렸을 때 |

`checkAll` 과 `integrationTest` 는 CI 의 두 잡이고 둘 다 관문이다. 나뉘어 있는 이유는 하나다 —
포맷 실수가 데이터베이스 뒤에서 기다리지 않게 하려고.

이 시스템의 동작은 사실상 전부 `integrationTest` 에서 증명된다. 부분 유니크 인덱스, pgvector 거리,
Flyway 마이그레이션 사슬은 목(mock)이 대신할 수 있는 것이 아니다. `checkAll` 만 초록이라고 해서
돌아간다는 뜻은 아니다.

`loadTest` 가 찍는 시간은 러너를 설명하지 시스템을 설명하지 않는다. 관문인 것은 그 시간이 아니라
단언이다 — 한 쌍에 동시에 쓰는 작성자들이 오류를 내지 않고, 순번에 구멍이 없고, 경합 속에서도 중복
제거가 수렴해야 한다.

### 계약 계층

`aimon-memory-client` 는 aimon-core 의 다섯 티어 `PeerMemory` 계약 스위트를 상속해서 돈다. 그 스위트는
`at.aimon.core:aimon-memory-testkit` 으로 오는데, 이 아티팩트에는 **아직 릴리스가 없다** — aimon-core
0.3.0 이 처음 담아 나간다. 대신 Central 의 스냅샷 저장소에 올라가 있고, 빌드가 그 좌표 하나만 거기서
풀도록 열어 뒀다. 그래서 **아무 기계에서나 돈다.** 새 클론에서도, CI 러너에서도.

이 계층은 `src/test` 가 아니라 `src/contractTest` 라는 별도 소스셋에 있다. 릴리스가 아닌 좌표를
`aimon-memory-client` 의 컴파일 클래스패스에 올리지 않으려는 것이고, testkit 이 어떤 이유로든 풀리지
않으면 빌드를 깨뜨리는 대신 이유를 한 줄 찍고 **스스로 건너뛴다.**

`checkAll` 이 이 21개를 이름으로 부르므로 따로 칠 것은 없다. 그래도 이 계층만 돌리고 싶다면,

```sh
./gradlew :aimon-memory-client:contractTest
```

**초록 CI 는 이제 계약 스위트가 통과했다는 뜻이다.** 예전에는 아니었다 — testkit 이 `~/.m2` 에만
있어서 CI 는 늘 이 계층을 건너뛰었고, 그래서 어댑터를 건드린 PR 은 손으로 돌린 결과를 적어야 했다.
스냅샷이 Central 에 올라가면서 그 예외가 없어졌다. 다만 건너뛰기가 사라진 것은 아니다. 로그에
건너뛰었다는 줄이 보이면 그 실행은 계약을 검증하지 않은 것이므로, 초록을 그대로 믿지 말 것.

---

## 포맷과 스타일

```sh
./gradlew format        # spotless 로 고친다
./gradlew checkFormat   # 고치지 않고 검사만
./gradlew checkStyle    # checkstyle (main 소스만)
```

포매터 설정은 `config/eclipse/eclipse-formatter.xml`, checkstyle 규칙은
`config/checkstyle/checkstyle.xml` 에 있다. 임포트 순서는 `java`, `javax`, `jakarta`, `org`, `com`, 나머지
순이고 spotless 가 강제한다.

줄 길이는 **120자**이고, 이 숫자를 적어 둔 세 곳이 전부 같은 말을 한다 —
`config/checkstyle/checkstyle.xml` 의 `LineLength`(`package`·`import`·URL 은 예외),
`config/eclipse/eclipse-formatter.xml` 의 `lineSplit`, 그리고 `.editorconfig` 의 `max_line_length`.
앞의 둘은 빌드를 깨고 `.editorconfig` 는 에디터에 알려 줄 뿐이니, 셋을 옮길 때는 같이 옮겨야 한다.

테스트 소스는 checkstyle 대상이 아니다. 포맷은 대상이다.

---

## CI 는 모델을 부르지 않는다

`AIMON_MEMORY_LLM_MODE` 의 기본값은 `replay` 이고, 빌드 관례가 모든 테스트 태스크에 그렇게 심는다.
녹화된 픽스처에 없는 호출은 실패한다.

```
replay   test-fixtures/llm/ 에서 꺼내 준다. 없으면 실패    (기본값, CI 가 도는 방식)
record   제공자를 실제로 부르고 픽스처를 쓴다
live     제공자를 부르고 아무것도 남기지 않는다
```

replay 가 빗나갔다면 프롬프트가 바뀐 것이다. **다시 녹화하기 전에 diff 를 읽어라.** 조용해질 때까지
재생성하는 것은 픽스처를 없애는 것과 같다. 녹화는 자격 증명이 필요한 의도적인 로컬 작업이고,
`./scripts/record-fixtures.sh` 가 그 일을 한다.

## 픽스처를 다시 쓸 때

```sh
./gradlew test -Daimon.memory.golden.update=true    # 골든 픽스처를 검사 대신 덮어쓴다
./gradlew test -Daimon.memory.eval.update=true      # 랭킹 기준선을 다시 만든다
```

라우트를 더하거나 그 모양을 바꿨다면 API 서술도 같은 스위치로 다시 만든다. 데이터베이스가 필요해서 이것만
`integrationTest` 다.

```sh
./gradlew :aimon-memory-api:integrationTest -Daimon.memory.golden.update=true
```

두 스위치 모두 **테스트를 정의상 통과시킨다.** 그래서 이 파일들을 건드린 diff 는 그것을 만들어 낸 코드와
같은 강도로 검토받아야 한다. 자세한 것은 `test-fixtures/README.md`.

---

## ADR 은 언제 쓰나

명세는 `docs/spec/aimon-memory-design.md` 하나다(ADR 0005). ADR 은 **구현이 명세와 달라진 지점과 그
이유를, 근거와 함께** 적는 곳이다. 다음 중 하나면 써라.

- 명세가 시키는 것과 다르게 갔다 (ADR 0004 — 반감기 공식이 그 예다)
- 되돌리려면 마이그레이션이 아니라 재작성이 필요한 구조 결정
- 라이선스·저작권 경계 (ADR 0005)
- 다른 저장소와의 계약, 특히 어느 빌드에도 드러나지 않는 것 (ADR 0007)

형식은 기존 것을 따른다. `docs/adr/NNNN-슬러그.md`, 첫 줄에 언어 배너, `# ADR NNNN — 제목`,
`**상태:** accepted · YYYY-MM-DD`, 그리고 맥락 / 결정 / 결과. 번호는 이어서 매긴다. 영어판
`NNNN-슬러그.en.md` 는 같은 자리에 `**Status:**` 와 Context / Decision / Consequence 를 쓴다.

결정이 뒤집히거나 그 안의 사실이 더는 참이 아니게 되면, 본문을 고치지 말고 **덧붙임을 붙인다** —
`## 덧붙임 · YYYY-MM-DD — 한 줄 요약`. 결정이 내려진 시점의 판단을 남겨 두는 것이 기록의 값이기
때문이다. ADR 0007 에 덧붙임이 셋 있다.

반대로, 코드를 읽으면 알 수 있는 것은 ADR 이 아니라 주석으로 간다. 이 저장소의 주석은 무엇을 하는지가
아니라 **왜 다른 방법이 아닌지**를 적는다. 그 관례를 따라 달라.

---

## 커밋 메시지

지금까지의 히스토리에서 뽑은 실제 관례다.

- **제목은 영어 한 줄.** 마침표 없이, 대문자로 시작하고, 무엇을 했는지 평서형으로 적는다. 두 가지를
  한 커밋에 담았으면 `, and` 로 잇는다 — `Build against aimon-core 0.2.4, and drop the composite build`.
- **본문은 왜인지를 적는다.** 무엇이 바뀌었는지는 diff 가 말한다. 소제목을 써서 길게 적어도 된다.
  이 저장소의 커밋은 대체로 길고, 그게 관례다.
- **검증한 방법을 적는다.** "Verified against the release rather than assumed: the 0.2.4 jar was
  opened and checked …" 같은 문장이 실제로 있다. 돌려 보지 않았으면 돌려 보지 않았다고 적어라.
- **남은 것도 적는다.** "Still unwired: …" 처럼 아직 안 된 것을 커밋이 스스로 밝힌다.
- 문서는 한국어지만 **커밋 메시지는 영어**다. 히스토리 12개가 전부 그렇다.

현재 히스토리의 모든 커밋에 `Claude-Session:` 트레일러가 붙어 있다. 그 세션에서 만든 커밋이면 붙이는
것이고, 기여자에게 요구하는 것은 아니다.

---

## PR 을 올리기 전에

- [ ] `./gradlew checkAll` 통과
- [ ] `./gradlew integrationTest` 통과 (Docker 필요)
- [ ] `RemotePeerMemory` 나 그 엔드포인트를 건드렸다면, `checkAll` 로그에서 `contractTest` 가
      건너뛰지 않고 실제로 돌았는지 확인했다
- [ ] 새 엔드포인트를 더했다면 `RoutePolicy` 에 항목이 있다 (없으면 빌드가 깨진다)
- [ ] 골든 픽스처나 랭킹 기준선이 바뀌었다면, 왜 바뀌어야 했는지 PR 에 적었다
- [ ] 명세와 달라진 결정이면 ADR 을 썼다
- [ ] 문서를 고쳤다면 한국어판과 `.en.md` 를 같이 고쳤다

## 문서 규칙

정본은 한국어, 영어판은 같은 이름에 `.en.md` 를 붙인다 — `CONTRIBUTING.md` 와 `CONTRIBUTING.en.md`.
두 판 모두 첫 줄에 상대 경로로 건 언어 배너가 있다. 한쪽만 고치면 나머지 한쪽이 조용히 틀린 말을 하게
되므로, 둘은 같은 커밋에서 움직인다.

경로는 옮기지 않는다. README·코드 주석·gradle 주석·커밋 메시지가 `docs/adr/0007-aimon-core-boundary.md`
같은 경로를 이름으로 부르고 있다.

## 행동 강령과 보안

- 이 프로젝트에 참여하는 모든 사람에게 [행동 강령](CODE_OF_CONDUCT.md)이 적용된다.
- **취약점은 이슈로 올리지 마라.** [SECURITY.md](SECURITY.md) 의 비공개 신고 절차를 따라라.

## 라이선스

기여한 것은 저장소와 같은 [Apache-2.0](LICENSE) 으로 배포된다. PR 을 여는 것이 그 동의다. 별도의 CLA 는
없다.
