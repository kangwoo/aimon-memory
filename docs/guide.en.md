[한국어](guide.md) · **English**

# User guide

This document walks through **actually calling aimon-memory**, start to finish. Why the concepts
look the way they do is in the [concepts document](concepts.en.md); the exact shape of the routes
and schemas is in [`openapi.json`](openapi.json); deployment and incident response are in the
[runbook](runbook.en.md).

Every example is `curl` — it ports to any language, and it hides nothing about what goes over the
wire.

---

## Contents

1. [Your first memory in ten minutes](#1-your-first-memory-in-ten-minutes)
2. [Tokens — the first thing you get stuck on](#2-tokens--the-first-thing-you-get-stuck-on)
3. [Workspaces, peers, sessions](#3-workspaces-peers-sessions)
4. [Two ways to put memory in](#4-two-ways-to-put-memory-in)
5. [Who observes whom](#5-who-observes-whom)
6. [Reading — which tier to use](#6-reading--which-tier-to-use)
7. [How to read explain](#7-how-to-read-explain)
8. [Filters](#8-filters)
9. [Provenance and the audit trail](#9-provenance-and-the-audit-trail)
10. [Tuning a workspace](#10-tuning-a-workspace)
11. [Working in Korean](#11-working-in-korean)
12. [Dreams and peer cards](#12-dreams-and-peer-cards)
13. [Error reference](#13-error-reference)
14. [Common mistakes](#14-common-mistakes)
15. [Using it from aimon-core](#15-using-it-from-aimon-core)

---

## 1. Your first memory in ten minutes

### What you need

- **JDK 21** — pinned in `gradle/libs.versions.toml`. The Gradle wrapper is committed, so `./gradlew`
  needs nothing installed beyond the JDK.
- **Docker** — for Postgres 16 + pgvector.

Provider credentials are **not** required. The embedder falls back to a local hashing implementation
and the model provider falls back to failing with a clear message the moment a completion is asked
for. That is enough to exercise every path.

### Starting it

```sh
docker compose up -d                          # postgres 16 + pgvector

export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
./gradlew :aimon-memory-api:bootRun &         # HTTP, 8080
./gradlew :aimon-memory-worker:bootRun &      # the worker
```

`AIMON_MEMORY_JWT_SECRET` is **required and has no default.** Without it the application refuses to
start; under 32 bytes it also refuses. Why it is that strict is in
[Tokens](#2-tokens--the-first-thing-you-get-stuck-on).

Check both processes on the **management** ports, not the service port.

```sh
curl -s localhost:9090/actuator/health   # API
curl -s localhost:9091/actuator/health   # worker
```

### The first token

`/v1/tokens` **narrows an existing token and cannot create one from nothing**, so the first token
has to be signed out of band. The script below is the same one `scripts/smoke.sh` uses.

```sh
export ADMIN_TOKEN=$(python3 - "$AIMON_MEMORY_JWT_SECRET" <<'PY'
import base64, hmac, hashlib, json, sys, time
secret = sys.argv[1].encode()
b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
now = int(time.time())
header  = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64(json.dumps({"iss": "aimon.memory", "iat": now, "exp": now + 3600,
                          "scope": "admin"}, separators=(",", ":")).encode())
sig = b64(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}", end="")
PY
)
```

### Put a memory in, take it back out

```sh
auth=(-H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json')

# 1. Post a message. The workspace, session and peer are created if absent.
#    ?wait=derive blocks until derivation drains (up to 30 s).
curl -s -X POST 'localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive' "${auth[@]}" \
  -d '{"messages":[{"peer":"alice","content":"I work at a bank in Gangnam, Seoul."}]}'

# 2. Ask what Alice knows about herself.
curl -s -X POST localhost:8080/v1/workspaces/demo/recall "${auth[@]}" \
  -d '{"query":"where does alice work","observer":"alice","observed":"alice","explain":true}'
```

If the second call returns a conclusion with the six-signal breakdown that produced it, you are up.

> **No conclusions and no model provider configured?** That is expected. The deriver is a model call
> and extracts nothing without a provider. To try recall anyway,
> [inject a conclusion directly](#option-b--inject-a-conclusion-directly) — that path uses no model.

### To sweep the whole thing at once

```sh
AIMON_MEMORY_JWT_SECRET=... ./scripts/smoke.sh
```

Health, workspace, ingestion, conclusion injection, Korean recall with the full signal breakdown,
entity provenance, audit trail — in order.

---

## 2. Tokens — the first thing you get stuck on

### Four nested scopes

| Scope | What it speaks for | Must be in the body |
|---|---|---|
| `admin` | everything | — |
| `workspace` | one workspace | `workspace` |
| `peer` | one peer inside one workspace | `workspace`, `peer` |
| `session` | one conversation inside one workspace | `workspace`, `session` |

**Wider satisfies narrower.** A route that requires a `peer` token also accepts `workspace` and
`admin`. Never the other way round.

### Narrowing only, never widening

`POST /v1/tokens` **cannot mint anything wider than itself.** Three widenings are closed:

- a broader scope than the caller holds
- a different workspace
- for a caller that is itself a peer token, a token naming someone else

What that buys: a service holding a workspace token can hand a browser a token for one conversation
without keeping an admin key anywhere near it — and the browser cannot widen it back.

```sh
# From a workspace token, mint one scoped to a single session and hand it to a client
curl -s -X POST localhost:8080/v1/tokens \
  -H "Authorization: Bearer $WS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scope":"session","workspace":"demo","session":"s1","lifetimeSeconds":3600}'
```

### `allowMemberRead`

A session token can **write only** by default. It may post messages but not read that session's
history. The narrow token is the one handed to a browser, and a default that granted reads would
make every such token a session-history export.

If you need reads, say so at issue time.

```json
{"scope":"session","workspace":"demo","session":"s1","allowMemberRead":true}
```

That lets the session token additionally read five routes: `GET .../sessions/{s}`, `.../context`,
`.../messages`, `.../peers`, and `POST .../messages/search`.

### Lifetime

- Default **12 hours** (`aimon.memory.jwt.lifetime`).
- Per request via `lifetimeSeconds`.
- Hard maximum **30 days**, no exceptions. **There is no revocation list, so expiry is the only
  thing that ends a leaked token.**

Setting `aimon.memory.jwt.lifetime` above 30 days fails at startup. If it did not, every token
issued without an explicit `lifetimeSeconds` — the normal case — would fail with a 400 blaming the
request.

### Who may speak as whom

When you post a message the speaker is in the **body**, not the path, so there is a separate check.

- A **peer token** may only write messages in its own name.
- A **session token** may write as **anyone** in its session. That is what it is for — whatever is
  transcribing the conversation has to transcribe all of the participants.

Had a peer token been able to sign someone else's name, that message would fan out into every
observer's memory as conclusions about that person, with nothing in the audit trail recording who
actually made the call.

---

## 3. Workspaces, peers, sessions

### You do not have to create them

All three are created on first use. One `POST .../sessions/s1/messages` creates the workspace, the
session, the peer and the session membership.

The explicit creation routes are for when you want to **supply configuration or metadata**.

```sh
# Create a workspace configured for Korean
curl -s -X POST localhost:8080/v1/workspaces/demo "${auth[@]}" \
  -d '{"configuration":{"language":"ko"},"metadata":{"tenant":"acme"}}'
```

### Listing and fetching

```sh
curl -s -G localhost:8080/v1/workspaces "${auth[@]}" --data-urlencode 'page=0' --data-urlencode 'size=50'
curl -s localhost:8080/v1/workspaces/demo/peers "${auth[@]}"
curl -s localhost:8080/v1/workspaces/demo/sessions "${auth[@]}"
```

`page` is zero-based (capped at 10000); `size` defaults to 50 and caps at 200. Going over a cap
**clamps rather than fails** — a client asking for more has not made an error worth failing on, and
pagination already says whether more remains. (A filter that cannot be honoured is a different
matter, and is still a 422.)

### Session membership

Who is in this conversation, and what each of them observes.

```sh
# Join with explicit observation switches
curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/s1/peers "${auth[@]}" \
  -d '{"peers":[{"peer":"alice","observeMe":true,"observeOthers":false},
                {"peer":"bot","observeMe":false,"observeOthers":true}]}'

curl -s localhost:8080/v1/workspaces/demo/sessions/s1/peers "${auth[@]}"       # current roster
curl -s -X DELETE localhost:8080/v1/workspaces/demo/sessions/s1/peers/alice "${auth[@]}"
```

`PUT` replaces the roster wholesale; `POST` adds to it. A `peer` **cannot be blank** — a
whitespace-only name is a 400. At most **100 peers** per request, and more is a 400 — the same number
as the message batch. The list is not truncated: on `PUT`, dropping the overflow would not merely
fail to add those peers, it would **remove them from the session** and take their access to the
transcript with it. A room larger than a hundred can be assembled with repeated `POST` calls, but
read the fan-out cost below before you do.

Membership has windows. A peer who left and came back keeps what they heard the first time without
gaining the gap in between, and a message is never filed under someone who was not present for it.

---

## 4. Two ways to put memory in

### Option A — post messages and let derivation happen

The ordinary path. Post the conversation as it flows.

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/s1/messages "${auth[@]}" \
  -d '{"messages":[
        {"peer":"alice","content":"I am going to Osaka next week.","metadata":{"turn":12}},
        {"peer":"bot","content":"Have you booked the flight?"}
      ]}'
```

- At most **100 messages** per request.
- `content` **cannot be blank and cannot exceed 32000 characters.** Both are a 400. The ceiling is
  8191 tokens at the usual four-characters-per-token approximation, which is where the embedder
  truncates its input — accepting longer text would store a tail that semantic recall can never see.
- `metadata` is free-form JSON. The system stores it and does not interpret it.
- **Returns immediately.** The model call happens later, in the worker.

**When do conclusions appear?** When one of the three batch gates opens. With defaults, three
seconds of quiet is enough (`batch.idle_flush_seconds`). Under load the token gate fires first (512).

**If you must read what you just wrote**, add `?wait=derive`.

```sh
curl -s -X POST 'localhost:8080/v1/workspaces/demo/sessions/s1/messages?wait=derive' "${auth[@]}" \
  -d '{"messages":[{"peer":"alice","content":"I am going to Osaka next week."}]}'
```

It blocks until the work units just queued drain, for up to **30 seconds**. Passing that is **not an
error** — the messages are stored and the work is queued; you just do not see the conclusions in
this response.

That it is per request matters: a global switch would cost everyone the batching win to serve the
few callers who need read-your-writes.

### Option B — inject a conclusion directly

For facts you already know. **No model is involved.** Use it for migrations, seed data, and things
the user typed into a profile field.

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/conclusions "${auth[@]}" \
  -d '{"observer":"alice","observed":"alice","session":"s1",
       "content":"Alice works at a bank in Gangnam, Seoul",
       "entities":["Seoul","Gangnam"],
       "expiresAt":"2027-01-01T00:00:00Z"}'
```

| Field | Required | Note |
|---|:-:|---|
| `observer`, `observed` | yes | Whose memory this is, and who it is about |
| `content` | yes | The fact, one line |
| `session` | no | Where it came from. Optional |
| `entities` | no | Linked as given. Omitted means this conclusion gets none — extraction runs only when conclusions are derived from messages. Elements **cannot be blank** — one empty name or `null` makes the request a 400 |
| `expiresAt` | no | The reconciler expires it once this passes |

Injected conclusions go through dedup too. If a row already says the same thing, it is reinforced
rather than duplicated.

Note that `confidence` is not taken from the caller — it comes from the level and the reinforcement
count.

### Deleting

```sh
curl -s -X DELETE localhost:8080/v1/workspaces/demo/conclusions/{id} "${auth[@]}"
```

A soft delete. A `delete` event is written, and dropping entity edges plus collecting orphaned nodes
is queued — the caller asked to delete one row, not to wait for a table scan.

---

## 5. Who observes whom

This section **sets your bill.** Read it before opening a large room.

### The two switches

- `observe_me` — the speaker keeps a memory of themselves, pair `(p, p)`.
- `observe_others` — a listener keeps a memory of the speaker, pair `(listener, speaker)`.

Both default to `true`, resolved **workspace → session → message, narrowest wins**.

### Why it is a bill

Every observing pair gets **its own work unit, its own batch and its own extraction call.** Fan-out
is over model calls, not only over storage.

A session of N mutually-observing peers costs **N + N(N−1)** calls per batch.

| Peers | Calls per batch |
|--:|--:|
| 2 | 4 |
| 3 | 9 |
| 5 | 25 |
| 10 | 100 |

Open a ten-person room on defaults and every batch costs a hundred calls. If that is not what you
meant, there is one lever.

```sh
# Only the bot observes others. People remember only themselves.
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"observe_others":false}}'

curl -s -X POST localhost:8080/v1/workspaces/demo/sessions/room/peers "${auth[@]}" \
  -d '{"peers":[{"peer":"bot","observeOthers":true}]}'
```

That makes it N + N. Why the cost was accepted rather than designed away is in
[ADR 0006](adr/0006-fanout-cost.en.md).

---

## 6. Reading — which tier to use

| If the question is | Use |
|---|---|
| "Give me conversational context for the next turn" | **Tier 0** `GET .../context` |
| "Where does Alice work?" | **Tier 1** `POST .../recall` |
| "List everything you know about Alice" | **Tier 2** `POST .../chat` |
| "Do these two facts contradict?" | **Tier 2** |
| "How has Alice changed over six months?" | **Tier 2** |

**When in doubt, Tier 1.** No model, answers in about 100 ms, same answer every time. Tier 2 has
Tier 1 as a tool, so using Tier 2 for a question Tier 1 can answer is paying more for the same
answer.

### Tier 0 — context

```sh
curl -s -G localhost:8080/v1/workspaces/demo/sessions/s1/context "${auth[@]}" \
  --data-urlencode 'tokens=4000'
```

| Parameter | Default | What it does |
|---|--:|---|
| `tokens` | 4000 | The whole budget, split 40% summary / 60% verbatim. Capped at 128000 |
| `target` | the session name | Whose point of view to render for |
| `perspective` | — | Rendering perspective |

The response carries `summary`, `messages`, and how the budget was actually spent
(`summaryTokens`, `messageTokens`, `tokenBudget`). `representation` is the same thing rendered for
dropping straight into a prompt.

When the budget is tight, **the oldest messages go.** The most recent turn is never truncated.

### Tier 1 — recall

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/recall "${auth[@]}" \
  -d '{
    "query": "where does alice work",
    "observer": "alice",
    "observed": "alice",
    "limit": 10,
    "explain": true,
    "threshold": 0.2,
    "filter": {"level": "explicit"}
  }'
```

| Field | Required | Note |
|---|:-:|---|
| `query` | yes | Natural-language query |
| `observer`, `observed` | yes | **Whose memory to look in.** No default |
| `limit` | no | How many to return. Default 10, capped at 100 |
| `explain` | no | The six-signal breakdown. **On by default** — pass `false` to turn it off |
| `threshold` | no | Floor on the **fused** score, overriding the workspace default for this request |
| `filter` | no | See [Filters](#8-filters) |

Response:

```json
{
  "analyzedQuery": "alice work",
  "candidatesConsidered": 42,
  "hits": [{
    "score": 0.7213,
    "conclusion": { "id": "...", "content": "...", "level": "explicit",
                    "timesDerived": 3, "lastReinforcedAt": "..." },
    "explain": { "sem": 0.81, "kw": 0.64, "ent": 0.90, "reinf": 0.58,
                 "rec": 0.99, "lvl": 1.0,
                 "weights": [0.5, 0.22, 0.13, 0.08, 0.05, 0.02],
                 "matchedEntities": ["Seoul", "Gangnam"] }
  }]
}
```

`analyzedQuery` shows how the analyzer split the query. If it looks wrong in Korean, that is your
cue to check the [language setting](#11-working-in-korean).

### Listing conclusions — unranked

When you want them all rather than ranked.

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions "${auth[@]}" \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice' \
  --data-urlencode 'page=0' --data-urlencode 'size=50'
```

### Tier 2 — chat

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/chat "${auth[@]}" \
  -d '{
    "question": "List everything you know about Alice",
    "observer": "bot",
    "observed": "alice",
    "reasoningLevel": "medium",
    "history": [{"role":"user","content":"the previous turn"}]
  }'
```

Only `assistant` is distinguished in a `history` entry's `role`, and it is matched
case-insensitively. Every other value — `user`, `system`, a misspelled `assistnat` — is read as a
user turn. Nothing beyond `minLength: 1` rejects it and nothing in the response reveals it, so the
caller assembling the transcript is the one that has to spell it right; that is why the schema
leaves `role` an open string. The value only shapes the prompt and is never stored, so a mis-rolled
turn costs that one answer and nothing after it.

`reasoningLevel` sets both the iteration cap and the toolset. Default `medium`.

| Level | Max iterations | Tools |
|---|--:|---|
| `minimal` | 1 | `recall` |
| `low` | 3 | + `search_messages` |
| `medium` | 6 | + `grep_messages`, `messages_by_date` |
| `high` | 10 | + `search_temporal`, `reasoning_chain` |
| `max` | 16 | + `entity_provenance` |

Alongside `answer`, the response carries `iterations`, `stoppedAtLimit`, and what was actually
called in `toolCalls`. `stoppedAtLimit: true` means the model ran out of iterations before
finishing — raise the level or narrow the question.

`history` accepts up to 500 turns. Each turn's `role` and `content` **cannot be blank** — an empty
turn is a 400, on `/chat` and `/chat/stream` alike. For structured output, pass a JSON schema in
`responseFormat`.

Streaming is `POST .../chat/stream` over SSE, with a five-minute timeout.

---

## 7. How to read explain

Every hit says why it is where it is. This is **on by default** — tuning without the breakdown is
guesswork, so you have to pass `"explain": false` to turn it off. Tuning the ranking means reading
this.

```
score = 0.50·sem + 0.22·kw + 0.13·ent + 0.08·reinf + 0.05·rec + 0.02·lvl
```

| Signal | What it says | If it is low |
|---|---|---|
| `sem` | Is the meaning close | The embedder did not connect this query to this sentence |
| `kw` | Do the query's words appear | Different wording. Exactly 0 means **none** of them appear |
| `ent` | Is a named entity from the query linked | No entity link, or one discounted for being linked to too much |
| `reinf` | How often confirmed | Said once and never again |
| `rec` | How recently confirmed | Old. A short half-life makes this drop fast |
| `lvl` | How directly grounded | `contradiction` (0.6) or `inductive` (0.8) |

### Three diagnostic patterns

**High `sem`, `kw` at zero.** The embedder connected them conceptually with no shared words. Normal,
and the semantic signal doing its job.

**Everything low except `ent`.** The entity layer rescued a hit the other signals missed. A common
shape for queries containing a proper noun.

**`sem` behaving oddly like a keyword.** Most likely no embedding provider is configured. Without
credentials the embedder falls back to local hashing, and `sem` then acts as a second keyword
signal — conceptual queries suffer most.

### Two things to know

**The denominator is constant.** Weights sum to 1.00 and stay there whether or not a signal fired. A
missing signal contributes zero, so the score drops honestly and the ordering among candidates is
untouched. That is what makes scores comparable across deployments and a fixed threshold meaningful.

**`threshold` cuts the fused score**, not the semantic score alone. Cutting on semantics alone would
discard exactly the rows an exact keyword match was about to rescue.

---

## 8. Filters

`recall` and message search share one filter language.

### Syntax

```json
{
  "level": "explicit",
  "times_derived": {"gte": 2},
  "created_at": {"gte": "2026-01-01T00:00:00Z"},
  "OR": [{"session_name": "s1"}, {"session_name": "s2"}]
}
```

- **A bare scalar means `eq`**; **a bare list means `in`**. Everything else names its operator.
- Nest with `AND`, `OR`, `NOT`. Several keys at the top level are an `AND`.

### Operators

| Operator | Meaning |
|---|---|
| `eq`, `ne` | equal / not equal. `ne` is null-safe (`IS DISTINCT FROM`) |
| `gt`, `gte`, `lt`, `lte` | ordering comparisons |
| `in`, `nin` | in / not in a list. `nin` does not drop NULL rows |
| `contains`, `icontains`, `starts_with` | text fields only |
| `exists` | value is a boolean; whether the column is non-null |

### Filterable fields — an allowlist

A name not on the list is **422**, never passed through. It is what keeps a filter from reaching a
column carrying another pair's scope, so new columns are opt-in by definition.

**Conclusions** (`recall`, `conclusions`):

```
id            session_name   level        content       content_norm   sync_state
confidence    times_derived  created_at   updated_at    last_reinforced_at   expires_at
```

**Messages** (`messages/search`):

```
id   session_name   peer_name   content   token_count   seq_in_session   created_at
```

### Limits

- Nesting depth **16**
- Predicate count **256**

Both are defences. Without a depth cap, a few hundred kilobytes of nested objects overflows the
stack and kills the request thread — a `StackOverflowError` is not a `RuntimeException`, so it
escapes error handling entirely. Without a node cap, a flat `OR` of fifty thousand terms parses fine
and hands the database a statement to plan.

### Coercion is strict

`{"times_derived": {"gte": "many"}}` is a **422**, not a zero.

For the same reason, **a rejected filter is 422 rather than a 200 with no rows.** The request parsed;
the predicate is the problem. An empty result set is how a caller concludes there is no data when in
fact their query was thrown away.

---

## 9. Provenance and the audit trail

Three routes that answer **"where did this come from?"**

### Backwards from an entity

```sh
curl -s -G localhost:8080/v1/workspaces/demo/recall/provenance "${auth[@]}" \
  --data-urlencode 'entity=Seoul' \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice' \
  --data-urlencode 'limit=10'
```

```
entity → linked conclusions → each one's premises → the source messages
```

Premises that cannot be resolved are listed in `unresolvedPremiseIds`. They do not silently vanish.

### One conclusion's history

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions/{id}/events "${auth[@]}" \
  --data-urlencode 'limit=100'
```

You get `actor` (who), `event` (what), and `beforeContent` / `afterContent` (how it changed).

| actor | Who it is |
|---|---|
| `deriver` | Extracted conclusions from messages |
| `dreamer` | Reasoned while nobody was watching |
| `dedup` | Reinforced or replaced during deduplication |
| `api` | A person called a route directly |
| `reconciler` | Embedding backfill, expiry sweep, queue tidying |

| event | What happened |
|---|---|
| `add` `reinforce` `replace` | created / re-confirmed / superseded by a better phrasing |
| `delete` `expire` `restore` | soft-deleted / expired / brought back |
| `sync_failed` | The embedding call failed, so it is invisible to semantic recall until retried |

Watch `sync_failed` in particular. That conclusion exists but the `sem` signal cannot see it.

### The reasoning chain

```sh
curl -s -G localhost:8080/v1/workspaces/demo/conclusions/{id}/chain "${auth[@]}" \
  --data-urlencode 'observer=alice' --data-urlencode 'observed=alice'
```

Walks up the premises a conclusion stands on. Meaningful for `deductive` and `inductive`
conclusions.

---

## 10. Tuning a workspace

```sh
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"language":"ko","recall.half_life_days":30}}'
```

`PUT` **replaces wholesale.** It is not a partial update.

### Every configuration key

| Key | Default | Range | What it does |
|---|--:|---|---|
| `language` | `und` | string | Selects the analyzer. `ko` / `en` / anything else is bigram |
| `recall.weights` | `[.50 .22 .13 .08 .05 .02]` | six numbers summing to 1.00 | Order is `[sem, kw, ent, reinf, rec, lvl]` |
| `recall.half_life_days` | 180 | (0, 100000] | Where the recency signal halves |
| `recall.threshold` | 0 | [0, 1] | Floor on the **fused** score |
| `recall.oversample` | 4 | [1, 100] | How many candidates each signal path fetches before fusion. See the thousand-row ceiling below |
| `recall.entity_top_k` | 10 | [1, 1000] | Entity neighbours considered for the boost |
| `recall.entity_sim_cut` | 0.5 | [0, 1] | Below this, an entity match contributes nothing |
| `dedup.cosine_distance_max` | 0.05 | [0, 2] | The furthest stage 3 will look |
| `dedup.unique_token_weight` | 10 | [0, 1000] | information = tokens + weight × distinct tokens |
| `batch.token_threshold` | 512 | [0, 1000000] | Batch goes when this many accumulate |
| `batch.max_age_minutes` | 30 | [0, 10080] | Batch goes after this long |
| `batch.idle_flush_seconds` | 3 | [0, 3600] | Batch goes after this much quiet |
| `observe_me` | `true` | boolean | Does the speaker remember themselves |
| `observe_others` | `true` | boolean | Does a listener remember the speaker |

The three batch values accept zero. Because the gate is an "or", zeroing one makes it always true —
that is how batching is turned off.

**`recall.oversample` is a multiplier.** What a signal path actually fetches is `limit × oversample`,
and the recall request chooses the limit (capped at 100). Where that product exceeds **a thousand it
is clamped to a thousand** — the number `recall.entity_top_k` already uses as its ceiling, which is
to say the width this system permits a single signal path. Clamped, not refused: `oversample: 100` is
still a legal setting and fetches exactly a thousand candidates at the default limit of 10. The
response does not report the clamp: `candidatesConsidered` is the size of the union the signal paths
produced, not the width they were asked for. The **log** does, at `debug` — set
`AIMON_MEMORY_LOG_LEVEL=DEBUG` if a ranking moved and you want to know whether this is why.

### Two traps

**An unknown key is a 422.** Better than a typo storing silently. `recall.half_life` used to store
cleanly, read back in the response body, and change nothing.

**A tuning key on a peer or session is a 422.** Peers and sessions have a `configuration` column
too, but tuning reads **only the workspace's**.

```sh
# This is a 422
curl -s -X PUT localhost:8080/v1/workspaces/demo/peers/alice/configuration "${auth[@]}" \
  -d '{"configuration":{"recall.half_life_days":5}}'
```

Storing an **unrecognised** key on a peer or session is still allowed: it is opaque client data, and
nothing about it claims to tune anything.

### When to change what

| Situation | Change |
|---|---|
| A coding agent that should forget within weeks | `recall.half_life_days` to around 30 |
| Recall dragging in too much noise | Raise `recall.threshold` |
| Batch ingestion rather than conversation | `batch.idle_flush_seconds` to 0 |
| Opening a big room and worried about cost | `observe_others` to `false` |
| Wanting to change the weights | **Only when the evaluation set says so.** Never on instinct |

---

## 11. Working in Korean

```sh
curl -s -X PUT localhost:8080/v1/workspaces/demo/configuration "${auth[@]}" \
  -d '{"configuration":{"language":"ko"}}'
```

That turns on Nori morphological analysis. Tags match by prefix, so `ko` and `ko-KR` both work. An
unknown tag falls back to bigrams rather than throwing.

### Set it early

`content_analyzed` is produced **at write time**. Change the language later and existing rows keep
whatever the old analyzer produced.

The good news is that this is a **re-index**, not a migration. The procedure is in the
[runbook](runbook.en.md#re-indexing).

### If proper nouns keep getting split

Attach a Nori user dictionary.

```sh
export AIMON_MEMORY_NORI_USER_DICT=/path/to/userdict.txt
```

### How to check

`analyzedQuery` in the recall response shows how the analyzer split the query. If that looks wrong,
the language setting is where to look first.

---

## 12. Dreams and peer cards

### Scheduling a dream

The dreamer normally runs on its own. To run one now:

```sh
curl -s -X POST localhost:8080/v1/workspaces/demo/dreams "${auth[@]}" \
  -d '{"observer":"bot","observed":"alice","type":"consolidate"}'

curl -s -G localhost:8080/v1/workspaces/demo/dreams "${auth[@]}" \
  --data-urlencode 'observer=bot' --data-urlencode 'observed=alice'
```

| `type` | What it does |
|---|---|
| `consolidate` | Deduction, induction, contradiction search. Produces new conclusions |
| `card_refresh` | Regenerates the peer card and nothing else |

Read the outcome from `status`, `produced` and `error` in the listing.

### Peer cards

A compact profile of one peer as seen by another.

```sh
curl -s -G localhost:8080/v1/workspaces/demo/peer-card "${auth[@]}" \
  --data-urlencode 'observer=bot' --data-urlencode 'observed=alice'

curl -s -X POST localhost:8080/v1/workspaces/demo/peer-card/refresh "${auth[@]}" \
  -d '{"observer":"bot","observed":"alice"}'
```

Every line carries one of four prefixes.

```
IDENTITY:      ATTRIBUTE:      RELATIONSHIP:      INSTRUCTION:
```

A malformed generation is dropped rather than stored. An empty card means the model ignored the
format.

Refreshing is cheap on purpose — it does not advance the dreamer's scheduling counters, so
refreshing often does not eat the budget for the reasoning pass that produces real knowledge.

---

## 13. Error reference

The body is always `{"code": "...", "message": "..."}`.

| Status | When | Where to look |
|--:|---|---|
| **400** | Body is not JSON, a parameter has the wrong type | The request itself |
| **401** | No token, or one that does not verify | `Authorization: Bearer`, the signing key, expiry |
| **403** | The token cannot reach this route, workspace, peer or session | Scope and narrowing |
| **404** | No such endpoint or resource | The path — and whether authentication passed |
| **405** | Wrong method | GET/POST/PUT/DELETE |
| **409** | Constraint violation | Duplicate key, concurrent write |
| **422** | **It parsed; the values are the problem** | Filter fields and types, configuration keys and ranges |
| **503** | No model or embedding provider configured | `AIMON_MEMORY_LLM_PROVIDER`, API keys |
| **500** | Anything else | The logs. This is a bug |

### Codes you will see

| `code` | Status | Meaning |
|---|--:|---|
| `bad_configuration` | 422 | Unknown configuration key, out-of-range value, weights that do not sum to 1.00 |
| `bad_filter` | 422 | A field outside the allowlist, a value of the wrong type, depth or node cap exceeded |
| `unauthorized` | 401 | No token, or one that does not verify |
| `forbidden` | 403 | The scope or the pair does not match |
| `not_found` | 404 | No such endpoint or resource |
| `method_not_allowed` | 405 | Wrong method |
| `bad_scope` | 400 | The `workspace` / `peer` / `session` that scope requires is missing from the body |
| `bad_lifetime` | 400 | Over 30 days, or not positive |
| `bad_reasoning_level` | 400 | Not one of `minimal` `low` `medium` `high` `max` |
| `batch_too_large` | 400 | More than 100 messages in one request |
| `bad_request` | 400 | The body is not JSON, or a field constraint failed — blank message `content` (and over 32000 characters), a blank `peer` name, more than 100 peers in one roster request, an empty turn in `history`, and a blank element in `entities` all land here |
| `bad_level` | 400 | A conclusion level outside the four values |
| `llm_not_configured` | 503 | Tier 2 or derivation asked for with no model provider |
| `fixture_miss` | 503 | Replay mode, and the call is not in the recorded fixtures |
| `missing_config` | 503 | Required configuration is absent. For `AIMON_MEMORY_JWT_SECRET` it fails at startup instead |
| `weak_jwt_secret` | — | The signing key is under 32 bytes. Fails at startup, not over HTTP |
| `unknown_llm_provider` | — | `AIMON_MEMORY_LLM_PROVIDER` (or `_LLM_FALLBACK`) is not `openai`, `anthropic` or `none`. Fails at startup |
| `unknown_embed_provider` | — | `AIMON_MEMORY_EMBED_PROVIDER` is not `openai` or `hashing`. Fails at startup |
| `default_db_password` | — | The shipped default password is in use against a database that is not on this host. Fails at startup |

**The last four have no HTTP status.** They are not answers to a request — the process does not come
up — so you read them in the startup log rather than from `curl`. All four are places where a quiet
fallback was refused. The provider names especially: a typo used to be treated as `none`, so
`AIMON_MEMORY_LLM_PROVIDER=openal` started cleanly and then answered every model call with
`llm_not_configured`. **`none` is a decision; a typo is not.**

A 422 message **names the values it would accept**: an unknown configuration key lists all fourteen
keys, an unknown filter field lists every field in that schema. Read the error body before reopening
the docs.

### A 403 you cannot explain

Check in this order.

1. Does the token's scope satisfy the route's minimum (each route's `description` in
   `openapi.json`)?
2. Can the token reach the workspace / peer / session in the path?
3. **Does `observer` match the token's peer?** This is the one people miss.

On point 3: `recall`, `conclusions`, `chat`, `dreams` and `peer-card` take observer and observed in
the **body or query string**, not the path. The auth interceptor only sees path variables, so a
separate check guards the observer side — **you cannot read Alice's memory with Bob's token.** The
observed side is deliberately unchecked: keeping a memory of someone is not a permission they grant.

---

## 14. Common mistakes

**Omitting `observer` / `observed` in recall.** There is no default. Which pair's memory you want
must be said. For a memory of oneself, pass the same value twice.

**Posting messages and calling recall immediately.** Derivation is asynchronous. Use `?wait=derive`
or wait for a batch gate.

**No conclusions, and the worker was never started.** The API only enqueues. The worker derives.

**Calling Tier 2 with no model provider.** That is a 503. Without credentials the provider fails
with a clear message the moment a completion is asked for.

**Writing a tuning key on a peer.** 422. Tuning reads the workspace's configuration only.

**Treating `PUT /configuration` as a partial update.** It replaces wholesale. Send the keys you want
to keep.

**Changing the language later and expecting old data to follow.** `content_analyzed` is produced at
write time. You need a re-index.

**Opening a ten-person room on defaults.** That is a hundred calls per batch. See `observe_others`.

**Filtering on a field not in the allowlist and expecting a 200 with no rows.** It is a 422 — which
is better, since an empty result would have read as "there is no data".

**Publishing the actuator ports.** Publish 8080 only. 9090 and 9091 have no authentication; the auth
interceptor covers `/v1/**` only.

**Turning on `AIMON_MEMORY_OPENAPI=true` in production.** That opens `/v3/api-docs` without a token.
The committed `docs/openapi.json` is the same document and it is versioned.

**Trying to create the first admin token through `/v1/tokens`.** That route only narrows. The first
token has to be signed out of band.

---

## 15. Using it from aimon-core

If you are an aimon-core application you do not need to call HTTP directly. `aimon-memory-client` is
the adapter.

```java
PeerMemory memory = new RemotePeerMemory(RemoteMemoryOptions.builder()
        .baseUri("https://memory.internal:8080")
        .token(tokens::current)          // called per request; tokens expire
        .agentPeer("assistant")          // who ASSISTANT-role messages are stored as
        .build());
```

| aimon-core tier | Endpoint |
| --- | --- |
| `SNAPSHOT` | `GET /v1/workspaces/{ws}/conclusions` |
| `SEARCH` | `POST /v1/workspaces/{ws}/recall` |
| `CHAT` | `POST /v1/workspaces/{ws}/chat` |
| `OBSERVE` | `POST /v1/workspaces/{ws}/conclusions` |
| `INGEST` | `POST /v1/workspaces/{ws}/sessions/{session}/messages` |

Pairs cross untranslated: aimon-core's subject is the observed and its observer is the observer. A
query that names no observer means the subject's own self-pair.

Three capability signals are `false`, and all three are differences rather than gaps.

- `narrowsBySession()` — conclusions outlive the session that produced them, so recall does not
  narrow by session
- `storesConfidence()` — an injected observation's confidence comes from level and reinforcement
  count rather than being taken from the caller
- ingestion enqueues rather than deriving, so a receipt never carries `derived`

The whole boundary is in [ADR 0007](adr/0007-aimon-core-boundary.en.md).

---

## What to read next

- [Concepts](concepts.en.md) — why it is shaped this way
- [Architecture](architecture.en.md) — goals, constraints, context, building blocks, quality gates,
  risks (arc42's twelve sections)
- [`openapi.json`](openapi.json) — the exact schema and scope for all 33 routes
- [Runbook](runbook.en.md) — deployment, observability, and what to look at when something is wrong
- [ADRs](adr/README.en.md) — where the implementation departed from the specification, and why
