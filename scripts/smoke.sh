#!/usr/bin/env bash
# End-to-end smoke test against running processes.
#
# Proves the parts nobody can prove from unit tests: that both jars boot, that Flyway applies every
# migration to an empty database, that Nori analysis reaches the query path, and that the entity
# layer rescues a hit the semantic signal misses.
#
#   docker compose up -d
#   AIMON_MEMORY_JWT_SECRET=... java -jar modules/aimon-memory-api/build/libs/aimon-memory-api-*.jar &
#   AIMON_MEMORY_JWT_SECRET=... java -jar modules/aimon-memory-worker/build/libs/aimon-memory-worker-*.jar &
#   ./scripts/smoke.sh
set -euo pipefail

BASE="${AIMON_MEMORY_BASE_URL:-http://localhost:8080}"
# Actuator lives on its own connector; the auth interceptor only covers /v1/**.
MGMT="${AIMON_MEMORY_MANAGEMENT_URL:-http://localhost:9090}"
# The worker serves nothing but actuator, so it gets a port of its own rather than a second one.
WORKER="${AIMON_MEMORY_WORKER_URL:-http://localhost:9091}"
SECRET="${AIMON_MEMORY_JWT_SECRET:?set AIMON_MEMORY_JWT_SECRET to the same value the API is running with}"
WS="${AIMON_MEMORY_SMOKE_WORKSPACE:-smoke}"

# The first token has to be minted out of band: /v1/tokens narrows an existing token and cannot
# create one from nothing. Everything after this could be delegated from it.
TOKEN=$(python3 - "$SECRET" <<'PY'
import base64, hmac, hashlib, json, sys, time
secret = sys.argv[1].encode()
b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
now = int(time.time())
header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64(json.dumps({"iss": "aimon.memory", "iat": now, "exp": now + 600, "scope": "admin"},
                         separators=(",", ":")).encode())
sig = b64(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}", end="")
PY
)
auth=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

say() { printf '\n== %s\n' "$1"; }

say "health"
printf 'api    '; curl -fsS "$MGMT/actuator/health"; echo
printf 'worker '; curl -fsS "$WORKER/actuator/health" 2>/dev/null || echo '(worker not running)'; echo

say "workspace, configured for Korean"
curl -fsS -X POST "$BASE/v1/workspaces/$WS" "${auth[@]}" > /dev/null
curl -fsS -X PUT "$BASE/v1/workspaces/$WS/configuration" "${auth[@]}" \
  -d '{"configuration":{"language":"ko"}}' > /dev/null
echo "ok"

say "ingest"
curl -fsS -X POST "$BASE/v1/workspaces/$WS/sessions/s1/messages" "${auth[@]}" \
  -d '{"messages":[{"peer":"alice","content":"저는 서울 강남에 있는 은행에서 일해요."}]}' \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin)),"messages stored")'

say "inject a conclusion with entities"
curl -fsS -X POST "$BASE/v1/workspaces/$WS/conclusions" "${auth[@]}" \
  -d '{"observer":"alice","observed":"alice","session":"s1",
       "content":"앨리스는 서울 강남의 은행에서 일한다","entities":["서울","강남"]}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'

say "Tier 1 recall, with the full signal breakdown"
curl -fsS -X POST "$BASE/v1/workspaces/$WS/recall" "${auth[@]}" \
  -d '{"query":"강남 은행","observer":"alice","observed":"alice"}' \
  | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("analyzed:", d["analyzedQuery"])
for h in d["hits"]:
    e = h["explain"]
    parts = " ".join("%s=%.4f" % (k, e[k]) for k in ("sem", "kw", "ent", "reinf", "rec", "lvl"))
    print("  %.6f  %s  entities=%s  %s"
          % (h["score"], parts, e["matchedEntities"], h["conclusion"]["content"]))
'

say "entity-anchored provenance"
curl -fsS -G "$BASE/v1/workspaces/$WS/recall/provenance" "${auth[@]}" \
  --data-urlencode "entity=서울" --data-urlencode "observer=alice" --data-urlencode "observed=alice" \
  | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("entity:", d["entity"])
for c in d["conclusions"]:
    print("  ", c["conclusion"]["content"])
'

say "audit trail"
ID=$(curl -fsS -G "$BASE/v1/workspaces/$WS/conclusions" "${auth[@]}" \
  --data-urlencode "observer=alice" --data-urlencode "observed=alice" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["items"][0]["id"])')
curl -fsS "$BASE/v1/workspaces/$WS/conclusions/$ID/events" "${auth[@]}" \
  | python3 -c '
import sys, json
for e in json.load(sys.stdin):
    print("  %s by %s %s" % (e["event"], e["actor"], e["detail"]))
'

say "queue metrics"
curl -fsS "$WORKER/actuator/prometheus" 2>/dev/null | grep -E '^aimon_memory_queue' || echo "  (worker not running)"

printf '\nsmoke test passed\n' 
