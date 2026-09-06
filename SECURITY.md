**한국어** · [English](SECURITY.en.md)

# 보안

## 취약점 신고

**공개 이슈로 올리지 마라.** 이슈는 공개되고, 고치기 전에 알려진 취약점은 그 자체로 사고다.

GitHub 의 비공개 신고 절차를 쓴다.

1. 이 저장소의 **Security** 탭
2. **Advisories** → **Report a vulnerability**
3. 아래 항목을 채워라

이 창구는 저장소 관리자에게만 보이고, 수정과 공개를 같은 자리에서 조율할 수 있다.

**지금은 이것이 유일한 창구다.** 연락처 이메일은 두지 않았다. 유지보수자가 한 명이라 지켜지지 않는
주소를 적어 두는 것이 없는 것보다 나쁘기 때문이다. GitHub 계정을 쓸 수 없다면, 취약점의 내용을 적지
말고 [Discussions](https://github.com/kangwoo/aimon-memory/discussions) 에 연락할 방법을 물어 달라.

신고에 담아 줄 것:

- 어떤 종류의 문제인지 (권한 우회, 토큰 처리, SQL, 격리 위반 …)
- 영향받는 커밋이나 버전
- 재현 절차. 요청 하나로 보일 수 있으면 그 요청
- 공격자가 무엇을 얻는지 — 무엇을 읽고, 쓰고, 넘어설 수 있는지
- 알고 있다면 완화책

아직 릴리스가 없고 유지보수자는 한 명이다. 정해진 응답 시간을 약속하지 않겠다. 지킬 수 없는 약속을
적느니 없는 편이 낫다. 접수했다는 사실은 확인해 주고, 수정과 공개 시점은 신고자와 함께 정한다.

## 지원 버전

| 버전 | 상태 |
|---|---|
| `0.1.0-SNAPSHOT` (`main`) | 개발 중. 여기서 고친다 |
| 릴리스된 버전 | 없음 |

Maven Central 에 올라간 것이 아직 없다. 지금 이 저장소를 쓰고 있다면 `main` 을 쓰고 있는 것이고,
수정은 `main` 으로 간다.

## 이 시스템의 보안 표면

배포하기 전에 알아야 할 것들이다. 전부 코드에 있고, 여기서는 어디에 있는지만 가리킨다.

### 서명 키에 기본값이 없다

`AIMON_MEMORY_JWT_SECRET` 은 필수이고 기본값이 없다. 비어 있거나 32바이트 미만이면 애플리케이션이
기동을 실패한다(`JwtService`).

이건 불편하자고 있는 게 아니다. `application.yml` 에 개발용 기본값을 두면 서명 키를 저장소에 공개하는
것과 같다. 환경변수를 빠뜨린 배포는 멀쩡히 떠서 그 키로 운영 토큰에 서명하고, 소스를 읽을 수 있는
누구나 admin 토큰을 만들 수 있게 된다. 돌아가는 시스템만 봐서는 그 상태와 정상 상태를 구분할 방법이
없다. 그래서 경고가 아니라 기동 실패다.

```sh
export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
```

### 토큰은 좁아지기만 한다

스코프는 `admin` → `workspace` → `peer` → `session` 네 단계로 중첩된다. 토큰은 **자기보다 넓은 것을
발급할 수 없다**(`TokenController`). 다른 workspace, 더 넓은 scope, 발급자가 대변하지 않는 peer 는
거부된다.

위임이 안전한 이유가 이것이다. workspace 토큰을 쥔 서비스가 대화 하나짜리 session 토큰을 만들어
브라우저에 건넬 수 있고, admin 키는 그 근처에 가지 않는다.

**폐기 목록은 없다.** 유출된 토큰을 끝내는 것은 만료뿐이라, 수명 상한이 30일로 박혀 있다
(`JwtService.MAX_LIFETIME`). 토큰이 새어 나갔다고 판단되면 `AIMON_MEMORY_JWT_SECRET` 을 갈아라 —
그러면 전부 무효가 된다.

### 라우트 허용목록

`RoutePolicy` 가 라우트마다 최소 스코프를 한 줄씩 명시한다. **항목이 없는 라우트는 거부된다.**
`RoutePolicyCoverageTest` 가 살아 있는 핸들러 매핑을 훑어서 표에 없는 라우트를 찾으면 빌드를 깬다.

즉 누가 호출해도 되는지 정하지 않고 엔드포인트를 추가하면 배포가 아니라 빌드가 먼저 실패한다.

### 격리는 쌍 단위다

모든 결론은 `(workspace, observer, observed)` 복합 외래키 아래에 있다. `alice` 가 자기를 기억한 내용과
`bot` 이 `alice` 를 기억한 내용은 서로 다른 저장소다. 이 경계를 넘는 읽기는 데이터 유출이므로,
그런 것을 찾았다면 이 문서가 말하는 취약점이 맞다.

### 감사 로그

결론을 바꾸는 모든 것이 이벤트를 남긴다. dreamer 는 아무도 보지 않을 때 기억을 편집하므로, 그 로그가
없으면 "이 믿음이 어디서 왔는가"에 답할 방법이 없다.

### 오류 응답은 저장된 값을 싣지 않는다

4xx·422 본문은 요청을 인용한다 — 호출자가 방금 보낸 값이라 새로 나가는 것이 없고, 그게 쓸모의 전부다.
**5xx 도 호출자가 보낸 것은 되풀이할 수 있고 — `store_failed` 는 찾던 entity 나 session 이름을 말한다 —
그 이상은 없다.** 그래서 파싱되지 않는 jsonb 컬럼, 모델 제공자의 응답 본문, 모델의 출력, 이 빌드가 지은
프롬프트, 서버의 파일 경로는 응답 본문에 들어가지 않고 서버 로그로만 간다. 본문에는 `code` 와, 제공자
오류라면 상태 코드만 남는다.

특히 세 가지를 노리고 있다. 하나는 **응답 DTO 가 일부러 빼 둔 것** — `internal_metadata` 는 어느 응답에도
없는데, 그 컬럼이 파싱되지 않으면 500 이 그것을 통째로 돌려주고 있었다. 둘은 **자격 증명의 흔적** —
OpenAI 의 401 본문은 설정된 API 키를 가운데만 가린 채 되돌려주고, 그 본문이 그대로 500 에 실렸다. 셋은
**지어진 프롬프트** — `FixtureMissException` 은 canonical request 전체와 fixture 디렉터리를 그대로 싣는
테스트 하네스용 진단이고, replay 는 옵트인이 아니다. `AIMON_MEMORY_LLM_MODE` 의 기본값이 `replay` 이고
`MemoryConfiguration` 이 설정된 제공자를 전부 `RecordingChatBackend` 로 감싸므로, 제공자만 설정하고
모드를 두지 않은 배포는 모든 모델 호출에 그 503 을 돌려주고 있었다. 이제 본문에는 코드만 남는다 — 그
5xx 에서도, 실패한 dream 을 나열하는 200 에서도. 예외 메시지를 호출자 쪽으로 옮겨 싣는 자리는
`ApiExceptionHandler` 하나가 아니다. `dreams.error` 는 `Dtos.DreamResponse` 가 200 으로 돌려주는
컬럼이라 5xx 경계에서만 막는 규칙은 빠져나갈 길을 하나 남긴다. 그래서 그 분리는 예외 자신
(`MemoryException.publicMessage`)에 있고, 옮겨 싣는 모든 자리가 그것을 쓴다.

> 제공자를 설정하고 `AIMON_MEMORY_LLM_MODE` 를 비워 두는 것은 지원되는 배포가 아니라 **설정 실수**다.
> 그 상태의 서비스는 `fixture_miss` 말고는 아무것도 돌려주지 않는다. 모드를 명시할 것.

**규칙은 예외의 출신을 따진다.** 위 문단은 이 빌드가 지은 예외(`MemoryException`)를 말한다. 그것이
아닌 것 — 드라이버·라이브러리·JDK 가 로그 독자에게 쓴 문장 — 은 아예 인용되지 않고 `an internal
failure; see the server log` 한 줄로 요약된다. Postgres 오류 하나가 실행된 문장 전체와 제약·릴레이션
이름을 담고, not-null·check 위반이면 실패한 행까지 덧붙이기 때문이다. HTTP 경로에서는
`ApiExceptionHandler` 가 예전부터 그것을 거절해 왔고(`constraint_violation`, 그리고 catch-all), 200
으로 나가는 두 자리 — 실패한 dream 의 `error` 와 `sync_failed` 이벤트의 `sync_error` — 도 이제 같다.
로그에는 전부 남는다.

이 규칙은 `ErrorBodyLeakTest` · `FixtureMissBodyTest` · `DreamErrorBodyLeakTest`(API),
`ProviderErrorLeakTest` · `ReplayHarnessTest` · `ToolLoopTest`(LLM), `CardRefreshConsumerTest` ·
`ReconcilerTest`(worker)가 고정한다. 어떤 응답 본문에서든 당신이 보낸 적 없는 값을 발견했다면, 그것은
이 문서가 말하는 취약점이 맞다.

### 자격 증명과 모델 호출

- 제공자 키(`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`)는 환경변수로만 읽는다. 저장소에 자격 증명은 없다.
- 테스트는 `AIMON_MEMORY_LLM_MODE=replay` 로 돌고, 이게 기본값이다. CI 는 모델을 부르지 않는다.
- `test-fixtures/llm/` 은 비어 있다. 녹화된 응답을 커밋할 때는 그 안에 무엇이 들어 있는지 확인해라 —
  프롬프트와 응답에는 대화 내용이 그대로 남는다.

### 운영

`docs/runbook.md` 의 "비밀값" 절이 배포에서 무엇을 어떻게 넣는지 다룬다.

## 범위 밖

- `docker-compose.yml` 의 자격 증명(`aimon_memory` / `aimon_memory`)은 로컬 개발용이다. 이걸로
  운영을 띄우는 것은 설정 실수지 취약점이 아니다.
- 자격 증명 없이 돌 때 임베더는 로컬 해싱 구현으로 물러난다. 검색 품질이 떨어지는 것은 의도된 동작이고
  README 에 적혀 있다.
- 제공자에게 보내는 내용 자체 — 무엇을 모델에 보낼지는 배포하는 쪽의 결정이다.
