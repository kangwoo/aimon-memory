# Operations

## Deploy

Two processes from one artifact set:

```sh
./gradlew :dyad-api:bootJar :dyad-worker:bootJar
java -jar dyad-api/build/libs/dyad-api-*.jar
java -jar dyad-worker/build/libs/dyad-worker-*.jar
```

They differ in how they should be treated:

| | API | Worker |
|---|---|---|
| Restart freely | yes | yes, but claims linger until TTL |
| Scale on | request rate | queue depth |
| Bound by | database latency | provider latency |
| Pool size | ~20 | ~2× concurrency |

The worker pool is small on purpose. A work unit holds a connection only around its queries, never
across the model call, so concurrency is bounded by `dyad.worker.concurrency` rather than by the pool.

## Migrations

Flyway runs at startup on both processes. Concurrent starts are safe — Flyway takes a lock — but the
first deploy of a new migration should go out on one instance.

Indexes arrive per phase (`V2`–`V5`) rather than all in `V1`, because an unused HNSW index still
slows every insert. Before adding another, check that something queries it.

`V2` and `V3` build HNSW indexes. On a large table that is slow and takes a write lock; use
`CREATE INDEX CONCURRENTLY` in a manual step for an existing deployment and mark the migration as
applied.

## Configuration that matters

| Setting | Default | When to change it |
|---|---|---|
| `recall.half_life_days` | 180 | Coding agents forget in weeks, assistants in years |
| `recall.weights` | `[.50 .22 .13 .08 .05 .02]` | After an evaluation set says so, never on a hunch |
| `recall.threshold` | 0 | Raise when recall returns too much noise; it cuts the *fused* score |
| `dedup.cosine_distance_max` | 0.05 | Technical corpora cluster tighter; loosen only with evidence |
| `batch.idle_flush_seconds` | 3 | Lower for conversational UX, raise to batch harder under load |
| `language` | `und` | `ko` or `en`; changing it needs a re-index (below) |

Workspace configuration is cached in the API process and evicted on write through the configuration
endpoint. Changing it directly in the database needs a restart.

## Common situations

**Conclusions are not appearing.** Check `queue` for unprocessed rows and `work_unit_claims` for a
stale claim. Claims expire after `dyad.worker.claim-ttl`; the reconciler removes them on its next
pass. A work unit stuck at `attempts >= dyad.worker.max-attempts` has been quarantined, and
`last_error` says why.

```sql
SELECT work_unit_key, count(*), min(created_at), max(attempts), max(last_error)
FROM queue WHERE processed = FALSE GROUP BY work_unit_key ORDER BY min(created_at) LIMIT 20;
```

**Recall returns nothing for a Korean query.** Check `analyzedQuery` in the response first — it is
there for exactly this. An empty or wrong analysis means the workspace `language` is not `ko`. Nori
splitting a proper noun is the other common cause; the fix is a user dictionary at
`DYAD_NORI_USER_DICT`.

**A conclusion exists but semantic search never returns it.** Its embedding did not land. The
reconciler retries automatically; `conclusion_events` carries a `sync_error` detail explaining why.

```sql
SELECT id, sync_state, left(content, 60) FROM conclusions
WHERE deleted_at IS NULL AND (sync_state <> 'synced' OR embedding IS NULL) LIMIT 20;
```

**Ranking changed unexpectedly.** `explain` on every hit shows which signal moved. Compare against
`test-fixtures/golden/` — if the fixtures still pass, the formula is intact and the data changed.

## Re-indexing

**After a language change:** re-analyse `content_analyzed` for the workspace, then let the FTS index
rebuild. There is no online path for this yet; it is a scripted pass over the workspace's conclusions.

**After an extraction prompt change:** entity quality is downstream of the prompt, so entities
extracted under the old one may need rebuilding. `conclusion_events.detail->>'prompt_version'`
identifies them. Clearing `entities.embedding` for the workspace makes the reconciler rebuild the
vectors on its next pass.

## Observability

Prometheus at `/actuator/prometheus` on the **management port** (`DYAD_MANAGEMENT_PORT`, default
9090), not the service port. The auth interceptor covers `/v1/**` only, so metrics served on the main
connector would be readable by anything that can reach the service. Publish 8080; do not publish 9090.

| Metric | Watch for |
|---|---|
| `dyad_worker_unit_seconds` | p99 climbing means provider latency, not database |
| `dyad_worker_items_total{task}` | flat while `queue` grows means claims are stuck |
| `dyad_worker_quarantined_total` | any increase is a poison batch worth reading |
| `hikaricp_connections_pending` | sustained non-zero means the pool is undersized |

The one alert worth having from day one is oldest unprocessed queue row older than
`batch.max_age` plus a margin. It catches a stalled worker, an exhausted provider quota and a claim
leak, all of which are otherwise silent.

## Index behaviour, measured

Numbers from 60k conclusions across 200 pairs on Postgres 16, and from one pair holding 50k.

| Index | Size at 60k | When the planner uses it |
|---|--:|---|
| `ix_concl_hnsw` | **433 MB** | Only once a single pair is large. At ~300 rows per pair it scans the pair instead — correctly, that is cheaper |
| `ix_concl_fts` | 3.5 MB | Selective text queries. Carries the pair columns via `btree_gin`; without them it was never chosen at all |
| `ix_concl_pair` | 416 kB | Nearly everything else |
| `ix_message_trgm` | — | `ILIKE` only. `position()` cannot use it, which is why `grep_messages` is written the way it is |

Two consequences worth planning around.

**The vector index is the dominant storage cost** — roughly 7 KB per conclusion, an order of magnitude
more than the row itself. Budget for it, and remember it only starts paying for itself when a pair
grows past a few thousand conclusions.

**A pair-scoped store defeats global indexes.** The pair predicate is so selective that an index over
the whole table has to scan every workspace's entries to reach one pair. Any new index on
`conclusions` intended for a pair-scoped query needs the scope columns in it, and
`IndexUsageTest` is where that gets checked.

## Load profile

`./gradlew :dyad-worker:loadTest` runs concurrent ingestion and recall and prints percentiles. Knobs:
`-Ddyad.load.pairs`, `-Ddyad.load.perPair`, `-Ddyad.load.readers`, `-Ddyad.load.writers`.

On a developer laptop against a container, 20 pairs of 100 conclusions with 32 concurrent readers:

```
recall   n=640   p50  53.4 ms   p95 137.1 ms   p99 201.9 ms
ingest   n=80    p50  57.9 ms   p95 516.7 ms   p99 531.7 ms
```

Ingest p95 is high **because the test deliberately points every writer at one session**. Sequence
allocation takes that session's row lock, so concurrent writers to a single conversation serialise —
which is the intended behaviour and the reason the sequence is gap-free. Writers spread across
sessions do not contend.

If recall p95 climbs in production, the order to check things: pool `pending` first, then whether one
pair has grown large enough that the planner switched to the HNSW index, then `ef_search`.

## Backups

Everything is in Postgres, so an ordinary base backup plus WAL is the whole story. `conclusion_events`
is append-only and is what makes a partial restore auditable — it deliberately has no foreign key to
`conclusions`, so the record of a deletion survives the row it describes.
