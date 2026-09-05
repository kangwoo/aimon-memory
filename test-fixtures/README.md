**한국어** · [English](README.en.md)

# 픽스처

코퍼스 두 벌이 따로 있고, 따로 있다는 것이 중요하다.

## `llm/` — 기록된 모델 호출

이 저장소에서는 비어 있다. 스위트에 기록된 호출은 전부 스크립트로 짠 백엔드에서 나온 것이고, 실제
제공자에게서 받은 코퍼스에는 자격 증명이 필요하다. 채우는 것은 `scripts/record-fixtures.sh` 다.

`(model, system, messages, tools, response_format)` 에 대한 SHA-256 으로 내용 주소를 잡는다. 제공자
왕복 한 번에 파일 하나라서, 다단계 툴 루프는 파일 여러 개가 되고 각각이 자기 입력으로 재생된다.

```
AIMON_MEMORY_LLM_MODE=replay   serve from here; a miss fails the test    (the default, and what CI runs)
AIMON_MEMORY_LLM_MODE=record   call the provider and write the fixture
AIMON_MEMORY_LLM_MODE=live     call the provider, record nothing
```

프롬프트를 의도적으로 바꾼 뒤에는 다시 기록한다.

```sh
AIMON_MEMORY_LLM_MODE=record OPENAI_API_KEY=... ./gradlew test --tests 'at.aimon.memory.engine.*'
```

프롬프트를 실수로 바꿔서 미스가 나는 것은 하네스가 일하고 있다는 뜻이다. 다시 기록하기 전에 diff 를
읽을 것.

## `golden/` — 기대되는 순위 출력

모든 신호와 융합 점수를 소수 6자리까지, 그리고 그 결과로 나온 순서까지.

```sh
./gradlew test -Daimon.memory.golden.update=true   # rewrite instead of assert
```

이 스위치는 모든 테스트를 정의상 통과시키므로, 이 파일들을 건드리는 diff 는 그것을 만들어 낸 코드와 같은
정도로 들여다봐야 한다.

## 이것들이 증명하는 것과 증명하지 않는 것

골든 픽스처는 공식이 명세대로 구현됐다는 것, 그리고 실수로 바뀐 것이 없다는 것을 증명한다. 가중치가
좋은지에 대해서는 아무 말도 하지 않는다 — 그건 nDCG/MRR 로 채점하는 라벨링된 평가 세트가 필요하고,
별도의 관문이다. 둘을 뭉뚱그리면 스위트는 초록인데 답은 나쁜 프로젝트가 된다.
