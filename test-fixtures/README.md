# Fixtures

Two separate corpora, and it matters that they stay separate.

## `llm/` — recorded model calls

Empty in this repository: every recorded call in the suite comes from a scripted backend, and a
corpus from a real provider needs credentials. `scripts/record-fixtures.sh` populates it.

Content-addressed by SHA-256 over `(model, system, messages, tools, response_format)`. One file per
provider round trip, so a multi-step tool loop is several files and each replays on its own inputs.

```
DYAD_LLM_MODE=replay   serve from here; a miss fails the test    (the default, and what CI runs)
DYAD_LLM_MODE=record   call the provider and write the fixture
DYAD_LLM_MODE=live     call the provider, record nothing
```

Re-record after a deliberate prompt change:

```sh
DYAD_LLM_MODE=record OPENAI_API_KEY=... ./gradlew test --tests 'dev.dyad.memory.*'
```

A miss after an accidental prompt change is the harness working. Read the diff before re-recording.

## `golden/` — expected ranking output

Every signal and the fused score to six decimal places, plus the resulting order.

```sh
./gradlew test -Ddyad.golden.update=true   # rewrite instead of assert
```

That switch makes every test pass by definition, so a diff touching these files needs the same
scrutiny as the code that produced it.

## What these prove, and what they do not

Golden fixtures prove the formula is implemented as specified and that nothing changed it by
accident. They say nothing about whether the weights are any good — that needs a labelled evaluation
set scored with nDCG/MRR, and it is a separate gate. Conflating the two is how a project ends up with
a green suite and bad answers.
