# ADR 0008: bounded, durable merchant feed jobs

Date: 2026-09-16. Implemented and acceptance-tested for the local P4a gate.

Input is UTF-8 NDJSON, one complete Offer per line. No CSV inference, remote URL
fetch, compression, archive, local path or real merchant data. Limit uploads to
1 MiB, 1,000 lines and 4 KiB per line; an optional final newline is not a row.
Reject structural size/encoding/checksum errors for the entire upload before
creating a job. Strict JSON/schema/domain errors become individual rejected rows.
Each valid row must match the URL's tenant/merchant scope and have a nonfuture
source timestamp at admission. Do not turn old source facts fresh on receipt.

POST /api/v1/feeds/{tenantId}/{merchantId} consumes application/x-ndjson with
Idempotency-Key, X-Content-SHA256 and X-Feed-Source headers. All identifiers use
the existing bounded identifier grammar; checksum is lowercase SHA-256 of the
exact upload bytes, including newlines. Same scoped key + checksum + source
returns the same job, even after completion; a changed request returns 409.
Admission serializes a short DB creation transaction, with 100 retained jobs
globally. Payloads are parsed before that transaction. Four concurrent uploads
per API process bound request memory. Content-Length is not trusted: enforce
the byte limit while reading the stream. Synthetic data only, loopback-only;
tenant path fields are scope, not verified authentication.

Persist immutable job provenance (scope, idempotency key, source label, raw-byte
checksum/length, creation time and row count), plus every line's number/hash.
Valid rows retain the normalized Offer snapshot; malformed rows retain only
their hash and fixed error code, never arbitrary raw text. Receipts expose
offer identity/version/source time and APPLIED, REPLAYED or REJECTED results.
Successful receipts reference immutable offer history. Payloads are not returned
by progress APIs. Feed storage and history retention are separate future gates.

One lease-fenced worker claims a job with SKIP LOCKED. A step processes at most
25 pending rows in input order, each in its own bounded transaction. Lock the
job and check its token/60-second DB lease before every row. Catalog writes,
outbox insertion, row receipt and job counters commit together. The existing
catalog identity lock/version rules remain authoritative. Expose an internal
transaction-required catalog primitive so a caught business conflict cannot
leave a nested transaction rollback-only. Do not catch SQL failures as bad rows.

Invalid rows are rejected at admission. Valid rows can later be rejected for
source-version conflicts. A crash preserves completed row receipts; expired
leases allow another worker to continue without duplicating history/outbox.
Transient failures leave a row pending with exponential retry delays. Five
consecutive failures pause the job; explicit retry records an operator reason
and resumes pending rows. Success resets consecutive failures. PAUSED is not a
completed import. Fixed codes replace exceptions/payloads in status and metrics.

States: QUEUED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, PAUSED, CANCELLED.
Cancellation locks the job, marks only pending rows cancelled, and invalidates
the lease. Already committed offers/outbox events are not reversed. Retry and
cancel are scoped, audited actions; at most 20 actions per job. Terminal results
and original provenance cannot be edited. No retention/deletion endpoint exists.

GET scoped job/list/rows routes expose bounded progress and keyset pagination.
POST scoped retry/cancel routes require a bounded reason identifier. Workers
are independently opt-in under postgres-local; accepting a feed does not start
Kafka/OpenSearch. A one-shot CLI supports deterministic progress/recovery when
the background worker is disabled. Existing rebuild commands force feed workers
off too. No browser UI or public deployment belongs to this gate.

Acceptance: parser/body bounds and sanitized row errors; real PostgreSQL
idempotency/concurrent admission, per-row rollback, lease takeover, partial
success/replay/conflict, retry/cancel; packaged HTTP admission/pagination; forced
JVM interruption with restart; feed → outbox → Kafka → verified search; full
regression suite and reproducible operator walkthrough. This is correctness
evidence, not measured capacity or exactly-once delivery across systems.

Reference: [PostgreSQL row locking](https://www.postgresql.org/docs/17/explicit-locking.html).
