# Durable merchant feeds

P4a local backend: upload a bounded NDJSON file, inspect durable per-row progress,
and recover work after worker failure. Synthetic data only, loopback-only and
unauthenticated. Tenant/merchant path parameters are scope, not authentication.
The UI, production identity/roles, retention and load experiments remain separate.

## Start explicitly

Use the database environment/setup from [POSTGRES.md](POSTGRES.md), then:

```sh
./mvnw verify
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=postgres-local --offers.feeds.enabled=true
```

Feed routes are disabled unless enabled. The command above accepts and inspects
jobs but deliberately leaves the background feed worker off. Publishing/indexing
remain independently disabled. To process jobs automatically, restart the API
with both `--offers.feeds.enabled=true` and `--offers.feeds.worker-enabled=true`.
One worker polls every 250 ms, handling at most 25 rows per claimed job per poll.
These are configuration bounds, not a throughput claim.

## Upload the mixed synthetic example

The included file has two source versions, an identical row replay and a malformed
row. Its historical source timestamps deliberately remain old after ingestion;
this example will not become a fresh VERIFIED search result merely by uploading.
Do not overwrite it with private merchant data.

```sh
FEED_SHA=$(shasum -a 256 examples/feed-mixed.ndjson | cut -d ' ' -f 1)
curl --fail-with-body -sS http://127.0.0.1:8081/api/v1/feeds/demo/synthetic \
  -H 'Content-Type: application/x-ndjson' \
  -H 'Idempotency-Key: mixed-example-v1' \
  -H 'X-Feed-Source: synthetic-fixture' \
  -H "X-Content-SHA256: $FEED_SHA" \
  --data-binary @examples/feed-mixed.ndjson | jq
```

New admission returns `202` with `{created:true,job:{...}}`. Copy `job.id` into
the commands below. Repeating the same scoped idempotency key, exact bytes and
source label returns `200`, `created:false`, and the **same** job, including
after completion. Reusing the key with different bytes or source label returns
409. Whitespace/line-ending changes affect the checksum and are different bytes.
Changing row source versions requires a new upload/key, not editing saved jobs.

Limits: 1 MiB per upload, 1,000 rows, 4 KiB per line, four simultaneous uploads per
API process and 100 retained jobs globally in the database. UTF-8 NDJSON only;
LF/CRLF and an optional final newline are supported. No compression, archives,
file paths, remote URLs, inferred CSV schema or spreadsheet execution.
Every nonfinal blank line is a rejected row, not silently ignored.

The byte limit is enforced while reading, including chunked requests without
Content-Length. Oversize inputs return 413 before job creation. Bad UTF-8,
empty bodies or checksum mismatch return 400. Unsupported content type/encoding
returns 415. Admission capacity returns 429. All headers except the checksum
use the existing 1–64-character identifier grammar (letters/digits/dot/_/-;
first character alphanumeric).

## Inspect and process

```sh
curl --fail-with-body -sS \
  'http://127.0.0.1:8081/api/v1/feeds/demo/synthetic/<job-uuid>' | jq
curl --fail-with-body -sS \
  'http://127.0.0.1:8081/api/v1/feeds/demo/synthetic/<job-uuid>/rows?after=0&limit=2' | jq
```

`job` includes immutable source label, exact-byte SHA-256/length, total rows,
creation time, state and counters (`processed`, `applied`, `replayed`, `rejected`,
`cancelled`). It also reports retry/lease timing without a lease token.

For deterministic one-shot processing with the same database environment:

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar --feed=step
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --feed=status --tenant=demo --merchant=synthetic '--job=<job-uuid>'
```

`step` processes the next eligible job in the **local database queue**, not a
client-selected tenant. Use only an isolated synthetic database you own. Each
invocation processes at most 25 rows, then exits; repeat deliberately while work
remains. `IDLE` can also mean jobs are leased, delayed or paused, not that every
job completed. Inspect job status. CLI forces web, feed background worker,
publisher, indexer and search off even if inherited environment flags enable them.
Snapshot/handoff commands likewise force feed workers off in their own process.

For the example in a fresh scope, final status is `COMPLETED_WITH_ERRORS`, with
four processed rows: two APPLIED, one REPLAYED and one REJECTED. Only two history
versions/outbox events are created. The head is version 2. A separate job that
replays older version 1 **after** version 2 exists will receive VERSION_CONFLICT;
global source-version ordering is preserved, not relaxed for feeds.

Row pagination uses the returned `nextAfter` as the next `after`; stop at null.
Every line has a stable number and raw-line SHA-256 (excluding LF, retaining CR
when present). Valid rows also expose offer ID, version and original source time.
Public reports never return stored payloads, raw invalid lines or exception text.
List jobs with GET on the scoped collection, `limit=1..100` (default 20), and
optional `after=<returned-job-uuid>`. Job listing is live UUID-keyset traversal,
not a frozen snapshot; newly admitted jobs can sort before an existing cursor.

## Outcomes, retries and cancellation

| State | Meaning |
|---|---|
| `QUEUED` | Waiting for a worker or retry delay; some rows may already be completed. |
| `RUNNING` | A token-fenced worker owns a 60-second database lease. |
| `COMPLETED` | All rows were applied or identical replays. |
| `COMPLETED_WITH_ERRORS` | All rows resolved; at least one was rejected. An entirely invalid feed reaches this state at admission. |
| `PAUSED` | Five consecutive transient processing failures; operator action required. |
| `CANCELLED` | Pending rows were cancelled; earlier committed offers remain. |

Row errors are fixed codes: INVALID_JSON, INVALID_OFFER, SCOPE_MISMATCH,
FUTURE_SOURCE, VERSION_CONFLICT. Unknown/missing fields, duplicates, wrong types
and invalid domain values are rejected. Lower versions or differing facts at the
same version produce VERSION_CONFLICT; correct them using a newer source version.
Source timestamps are checked at upload, not made fresh at processing time.

Transient transaction failures leave the row pending. The worker retries after
1, 2, 4 and 8 seconds, then pauses on the fifth failure. Successful processing
resets consecutive failures. Status records DATABASE_ERROR, not sensitive details.
After fixing the cause, explicitly retry a PAUSED job:

```sh
curl --fail-with-body -sS -X POST \
  'http://127.0.0.1:8081/api/v1/feeds/demo/synthetic/<job-uuid>/retry' \
  -H 'Content-Type: application/json' --data '{"reason":"dependency-restored"}' | jq
```

Cancel unfinished work with the same JSON shape at `/<job-uuid>/cancel`.
Cancellation waits for a current row transaction to finish, marks remaining
pending rows CANCELLED, and fences the worker. **It does not undo committed
offers, tombstones, outbox publication or search updates.** Retry is not undo;
terminal jobs cannot be retried or cancelled. Invalid-state actions return 409.

GET `/<job-uuid>/actions` returns the retained RETRY/CANCEL audit, including the
reason identifier and database timestamp. There are at most 20 actions per job.
This is a local operator audit, not proof of an authenticated actor.

## Crash safety and shutdown

Every row locks the job, checks its lease token, and applies catalog/history,
outbox, row receipt and counters in one transaction. Business conflicts are row
results; SQL/storage errors roll the entire row back. A lost response or worker
crash cannot create a successful catalog write without its durable receipt.
Already committed rows are skipped after restart; another worker can claim an
expired lease. Do not manually expire leases in a real operator workflow.

Stop the API normally with Ctrl-C before stopping dependencies. Restart with
the same database to resume QUEUED/expired RUNNING jobs. A stale worker cannot
checkpoint or release a replacement's lease. Multiple worker correctness is
tested locally; no multi-node throughput or HA claim is made.

Worker counters use `offers.feeds.polls` with bounded result labels. They count
poll outcomes, not imported rows. No public metrics endpoint is enabled.

To connect completed rows to search, enable the publisher/search/indexer using
[SEARCH.md](SEARCH.md) in addition to feed flags. Feed completion means durable
source ingestion, **not** Kafka acknowledgement or immediate search visibility.

## Retention and proof

Provenance, inputs, terminal receipts and action records are protected against
ordinary mutation/deletion. The 100-job guard includes terminal jobs and never
evicts idempotency evidence. Original malformed bodies are not stored. The bound
is for feed storage, not all history/outbox/index growth. Cleanup/retention needs
a reviewed future workflow; do not bypass triggers or delete volumes to free
capacity in a database containing wanted work.

See [ADR 0008](adr/0008-durable-merchant-feeds.md) and
[acceptance evidence](validation/P4a.md). Tests use isolated databases and actual
packaged JVMs, including a forced worker kill. Production auth, role separation,
image remediation, restore drills, representative load and the React UI are not
completed by this backend slice.
