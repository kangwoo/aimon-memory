#!/usr/bin/env bash
# Produce a blank scoring sheet from the Tier 2 evaluation set.
#
# The answers themselves need a real provider and a person to grade them; this only lays out the
# queries and the rubric so that grading is consistent between sessions and between people.
#
#   ./scripts/dialectic-sheet.sh > /tmp/dialectic-$(date +%F).md
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT/test-fixtures/eval/dialectic.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))

print("# Dialectic scoring sheet\n")
print(data["note"], "\n")
print("## Rubric\n")
print(f"Scale: {data['rubric']['scale']}\n")
print("| criterion | weight | question |")
print("|---|--:|---|")
for c in data["rubric"]["criteria"]:
    print(f"| `{c['id']}` | {c['weight']} | {c['question']} |")
    if c.get("note"):
        print(f"| | | *{c['note']}* |")

criteria = " | ".join(c["id"] for c in data["rubric"]["criteria"])
header = " | ".join("---" for _ in data["rubric"]["criteria"])

by_category = {}
for q in data["queries"]:
    by_category.setdefault(q["category"], []).append(q)

for category, queries in by_category.items():
    print(f"\n## {category}\n")
    for q in queries:
        print(f"### {q['id']} — {q['query']}\n")
        print(f"**Passing looks like:** {q['expect']}\n")
        print("```\nanswer:\n\ntool calls:\n```\n")
        print(f"| {criteria} |")
        print(f"| {header} |")
        print("| " + " | ".join(" " for _ in data["rubric"]["criteria"]) + " |")
PY
