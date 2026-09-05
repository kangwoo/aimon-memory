<!--
제목은 커밋 제목 관례를 따라 주세요 — 영어 한 줄, 마침표 없이, 무엇을 했는지.
관례는 CONTRIBUTING.md 의 "커밋 메시지" 절에 있습니다.
-->

## 무엇을, 왜

<!-- 무엇이 바뀌었는지는 diff 가 말합니다. 왜 그래야 했는지, 그리고 왜 다른 방법이 아닌지를 적어 주세요. -->

## 관련 이슈

<!-- Closes #123 / Refs #123 / 없으면 지우세요 -->

## 어떻게 확인했나

<!--
돌린 것과 그 결과를 적어 주세요. 돌리지 않았으면 돌리지 않았다고 적는 편이 낫습니다.
이 저장소의 커밋은 "Verified against the release rather than assumed: ..." 처럼
확인 방법을 본문에 남기는 관례가 있습니다.
-->

- [ ] `./gradlew checkAll`
- [ ] `./gradlew integrationTest` (Docker 필요)
- [ ] `./gradlew :aimon-memory-client:contractTest` — `RemotePeerMemory` 나 그 엔드포인트를 건드렸다면.
      `checkAll` 이 이미 부르므로 CI 도 돌립니다. 로그에서 건너뛰지 않았는지만 확인해 주세요
- [ ] `./gradlew :aimon-memory-worker:loadTest` — 워커나 큐를 건드렸다면
- [ ] `./scripts/smoke.sh` — 부팅·마이그레이션·HTTP 경로를 건드렸다면

## 체크리스트

- [ ] 새 엔드포인트가 있다면 `RoutePolicy` 에 항목을 넣었다 (없으면 `RoutePolicyCoverageTest` 가 빌드를 깬다)
- [ ] 골든 픽스처나 랭킹 기준선이 바뀌었다면, **왜 바뀌어야 했는지** 위에 적었다
      (`-Daimon.memory.golden.update=true` 는 테스트를 정의상 통과시킨다)
- [ ] 프롬프트를 바꿔서 replay 가 빗나갔다면, 다시 녹화하기 전에 diff 를 읽었다
- [ ] 명세와 달라진 결정이면 `docs/adr/` 에 ADR 을 썼다
- [ ] 문서를 고쳤다면 한국어 정본과 `.en.md` 를 같은 커밋에서 함께 고쳤다
- [ ] 설정 키·환경변수를 더했다면 `docs/runbook.md` 와 README 를 갱신했다
- [ ] 공개 API 가 바뀌었다면 `CHANGELOG.md` 의 `Unreleased` 에 적었다

## 남은 것

<!--
아직 안 된 것, 후속으로 남긴 것. 없으면 "없음".
이 저장소의 커밋은 "Still unwired: ..." 처럼 스스로 밝히는 관례가 있습니다.
-->
