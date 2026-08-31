#!/usr/bin/env bash
# Re-record the LLM fixture corpus against a real provider.
#
# Recording is a deliberate act, never something CI does. A miss in replay means a prompt changed;
# the response to that is to read the diff and decide, not to regenerate until the suite goes quiet.
#
#   OPENAI_API_KEY=... ./scripts/record-fixtures.sh
#   OPENAI_API_KEY=... ./scripts/record-fixtures.sh 'dev.dyad.memory.derive.*'
set -euo pipefail

PATTERN="${1:-dev.dyad.memory.*}"
: "${OPENAI_API_KEY:?set OPENAI_API_KEY (or ANTHROPIC_API_KEY and DYAD_LLM_PROVIDER=anthropic)}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURES="$ROOT/test-fixtures/llm"

echo "recording $PATTERN into $FIXTURES"
before=$(ls -1 "$FIXTURES" 2>/dev/null | wc -l | tr -d ' ')

DYAD_LLM_MODE=record \
DYAD_LLM_PROVIDER="${DYAD_LLM_PROVIDER:-openai}" \
"$ROOT/gradlew" test --tests "$PATTERN" --rerun-tasks

after=$(ls -1 "$FIXTURES" 2>/dev/null | wc -l | tr -d ' ')
echo
echo "fixtures: $before -> $after"
echo "Read the diff before committing. A fixture that changed without a prompt change is a finding."
