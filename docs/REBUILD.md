# Resumable shadow-index rebuild

This is P3c2a: capture a PostgreSQL snapshot, build a separate OpenSearch index,
and validate it. **It does not catch up Kafka or switch the live index.** Even
`SNAPSHOT_VALIDATED` is not a promotion approval. Ingestion can continue, so the
snapshot may already differ from current source facts.

Use synthetic data only. Existing pinned-image vulnerabilities, authentication
and deployment gates still apply. This is a local operator command, not a public
API. All steps are explicit one-shot processes; nothing is scheduled afterward.

## Prepare

Follow [POSTGRES.md](POSTGRES.md) to set `APP_DATABASE_URL`, `APP_DATABASE_USER`
and `APP_DATABASE_PASSWORD`. Start PostgreSQL and the opt-in OpenSearch service
from [SEARCH.md](SEARCH.md). Kafka is not required for this snapshot-only step.
The API/search/indexer can remain running independently if you already use them.

```sh
docker compose --profile search up -d --wait postgres opensearch
./mvnw verify
```

The rebuild command always forces `postgres-local`, no web server, and disabled
publisher/indexer/search workers in its own process, including when their flags
are inherited from the environment. It does not stop other application processes.
Only the documented command flags below are accepted; activation overrides and
duplicate flags fail before opening the database.

## Capture a durable snapshot

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar --rebuild=create --max-offers=10000
```

Copy `job.id` from the JSON response. The response also reports the generated
`shadowAlias` and `promotable:false`. The command captures immutable references
to every current head, including deleted offers, in one database statement.
It does not project from Kafka or make old source timestamps fresh.

`--max-offers` is optional, defaults to 100000, and must be 1–100000. Capturing
more than the configured limit rolls back the entire new job. At most ten jobs
may be retained, including invalid/validated jobs. Cleanup is not implemented;
do not work around the guard by deleting immutable rows or disabling triggers.
A future audited retention workflow must address both database and index storage.

## Build, validate, and inspect

Replace `<job-uuid>` with the returned UUID, keeping the same database and index
endpoint for every step:

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --rebuild=step --job=<job-uuid> --endpoint=http://127.0.0.1:9201
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --rebuild=status --job=<job-uuid>
```

Each step does at most one 100-offer batch or the final count check. Repeat the
step command deliberately while it returns `PROGRESSED`. It is not an infinite
retry loop. Progress is durable across process restarts:

| State | Meaning |
|---|---|
| `BUILDING` | Project pages using source external versions; checkpoint only after all bulk items acknowledge. |
| `VALIDATING` | Freeze the shadow's writes, reread every saved snapshot, then check total documents including tombstones. |
| `SNAPSHOT_VALIDATED` | Snapshot matches; index remains write-blocked and is **not promotable**. |
| `INVALID` | Ownership/content/version/count mismatch; inspect the fixed failure code. No live alias changed. |

For one offer, expect three steps: build the page, validate the page, check the
count. An empty snapshot needs two steps. A 103-offer snapshot needs five: two
build pages, two validation pages, one count check. These are algorithmic step
counts, not measured performance claims.

The private alias is `offers-build-<uuid>` and concrete index is
`offers-build-<uuid>-v1`. Resume verifies the exact ownership marker and private
alias. The command never overwrites an unrelated index, adds a live alias,
deletes a document/index, commits Kafka offsets or changes source data.

## Failure and retry

Exit codes:

- `0`: successful create/status/progress/validation, or an already validated job.
- `2`: invalid options, missing job/dependency setup error, or an INVALID job.
- `3`: retryable step failure, active lease, or ownership lost to a replacement.

Read `job.state`, `job.lastError`, `job.projected`, `job.validated` and
`job.leaseUntil`. `BUSY_OR_TERMINAL` is intentionally accompanied by job state so
an active lease is distinguishable from a terminal result. No lease token or
offer body is returned. Failure messages do not include exception text.

The database owns the 60-second lease clock. After a killed process, wait for
lease expiry and invoke the same step again. Do not manually expire a lease in
a real operator workflow. A crash after index acknowledgement but before the
checkpoint can replay a batch; source-version conflicts are safe no-ops and
full validation still checks content. Partial bulk failure never checkpoints
the page. Transient failure releases the lease with `STEP_FAILED` when the
database is reachable; otherwise lease expiry handles recovery.

`INVALID` jobs do not restart. Investigate without editing the live alias. A
new snapshot needs a new job; this does not automatically clean up the old one.
If the index is unavailable, the live application may independently return 503;
this command cannot provide availability guarantees.

## What is deliberately absent

No catch-up, promotion, rollback or cleanup command exists yet. Do not manually
unblock or promote these snapshots. A safe future online run must capture Kafka
boundaries before snapshot creation, handle topic/retention/partition changes,
replay missed updates, fence the live indexer handoff, and reconcile interrupted
alias changes. An atomic alias API call alone does not establish those properties.

See [ADR 0005](adr/0005-resumable-shadow-rebuild.md) and
[validation evidence](validation/P3c2a.md). Stop local dependencies when finished
using the normal [search shutdown instructions](SEARCH.md#stop-without-deleting-data);
preserve their volumes and avoid global Docker cleanup commands.
