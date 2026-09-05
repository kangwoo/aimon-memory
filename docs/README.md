**한국어** · [English](README.en.md)

# aimon-memory 문서

대화형 에이전트를 위한 메모리 시스템. 저장하는 모든 사실은 방향이 있는
**`(observer, observed)` 쌍**에 속한다 — `alice` 가 자기 자신을 기억한 내용과 `bot` 이 `alice` 를
기억한 내용은 서로 섞이지 않는 별개의 저장소다.

저장소 자체는 [GitHub](https://github.com/kangwoo/aimon-memory) 에 있다. 빌드하고 띄우는 법은
그쪽 README 에 있다.

## 어디부터 읽을 것인가

| 이런 걸 찾는다면 | 이 문서 |
|---|---|
| **호출해 보고 싶다** | [사용자 가이드](guide.md) — 토큰 발급부터 recall 튜닝까지 `curl` 로 따라간다 |
| **왜 이렇게 생겼는지 알고 싶다** | [개념](concepts.md) — 쌍, 세 티어, 여섯 신호, 중복 제거, 망각 |
| **아키텍처를 봐야 한다** | [아키텍처](architecture.md) — 목표·제약·컨텍스트·빌딩블록·품질·리스크 (arc42) |
| **운영해야 한다** | [런북](runbook.md) — 배포, 마이그레이션, 관측, 부하 |
| **라우트 하나의 정확한 형태** | [`openapi.json`](openapi.json) — 33개 라우트, 스키마, 스코프 |
| **왜 그렇게 정했는지** | [ADR](adr/README.md) — 명세에서 벗어난 자리와 그 근거 *(GitHub)* |
| **원본 명세** | [명세와 계획](spec/README.md) — 2026-08-31 시점에 얼어 있다 *(GitHub)* |
| **대시보드를 걸고 싶다** | [`dashboards/aimon-memory-overview.json`](dashboards/aimon-memory-overview.json) — Grafana 대시보드. 볼 메트릭은 [런북](runbook.md#관측)에 |
| **픽스처가 무엇을 증명하나** | [픽스처](../test-fixtures/README.md) — 코퍼스 두 벌과 각각이 증명하는 것 *(GitHub)* |

aimon-core 쪽에서 왔다면 [ADR 0007](adr/0007-aimon-core-boundary.md) 부터. 두 저장소 사이의 경계를
긋는 문서다.

**결정 기록과 명세는 이 사이트에 올리지 않는다.** 둘 다 이 프로젝트가 어떻게 여기까지 왔는지에 대한
기록이지, 이것이 무엇이고 어떻게 쓰는지를 찾아 온 사람이 볼 것은 아니기 때문이다. 저장소에는 그대로
있고 위 링크는 GitHub 으로 간다.

## 문서마다 소유하는 것이 있다

같은 사실이 두 곳에 나오면 한쪽은 요약이고, 요약에는 정본 링크가 붙는다. 규칙과 그 근거는
[ADR 0008](adr/0008-arc42-architecture-doc.md) 에 있다.

정본은 한국어다. 모든 문서에 같은 경로의 `.en.md` 영어판이 있고, 화면 위쪽에서 언어를 바꿀 수 있다.
예외는 `spec/` 의 두 문서로, 한국어만 있다 — 명세가 하나여야 [ADR 0005](adr/0005-agpl-boundary.md) 의
클린룸 주장이 성립하기 때문이고, 그 사정은 [`spec/README.md`](spec/README.md) 에 적어 뒀다.
