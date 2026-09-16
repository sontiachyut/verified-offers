# ADR 0005: durable, resumable shadow-index rebuild

Date: 2026-09-16. Accepted for P3c2a; P3 remains open.

Split the online rebuild gate into a durable snapshot/shadow validation slice
and a later Kafka catch-up/fenced-cutover slice. This slice never changes the
live alias, consumer offsets, catalog facts or publisher state. A validated
snapshot is **not ready for promotion** while ingestion continues.

Capture all current PostgreSQL head identities/versions, including deletions,
in one INSERT…SELECT statement. Store ordered snapshot references to immutable
offer_version rows, not a long-running transaction or a list in JVM memory.
Record statement time and count atomically. Subsequent source updates, new keys
and tombstones cannot change this snapshot. Page by stored ordinal, at most 100
offers per step; no OFFSET scans. Guard local disk use with a 100,000-offer
snapshot cap and ten retained jobs. A failed/oversized capture rolls back
entirely. Cleanup/retention remains explicit future operator work.

Each job has a random UUID, generated private shadow alias/index names, source
count, projected/validated checkpoints, state and fixed failure code. States:
BUILDING → VALIDATING → SNAPSHOT_VALIDATED; validation mismatch → INVALID.
The last state deliberately does not say READY or COMPLETE. Transient errors
leave the checkpoint unchanged for retry. An operator step processes one bounded
page, or the final count check; it never retries forever.

Use a 60-second PostgreSQL lease and random ownership token. Claim/checkpoint/
release are short database operations. No database transaction spans index I/O.
Only the current unexpired token can advance state or release ownership. A crash
after index acknowledgement before checkpoint replays the same page; external
source versions make that replay idempotent. Competing/expired workers cannot
advance another owner's checkpoint. Old in-flight writes remain possible but
carry identical immutable snapshot content; this is not exactly-once execution.

Provision only `offers-build-<uuid>-v1` with private write alias
`offers-build-<uuid>`, strict shared mapping and `_meta` ownership marker. Resume
checks the exact index, sole private alias, write flag and marker before adopting
an existing index. Never overwrite, delete or attach a live alias. Bulk writes
require an alias and inspect every item; only external-version conflicts are
no-ops. Other per-item failures fail the step. A UUID is ownership metadata, not
an authentication boundary against a local administrator.

Once BUILDING is durably checkpointed, set the private index's write block before
each validation step. The block remains after validation; this slice never
unblocks it. A crash before/after blocking is safely repeatable. Delayed expired
builders can only replay identical content or receive a blocked-write error.
Validation rereads every snapshot page with real-time multi-get, checking exact
identity, full source payload and external version. After all pages match,
refresh and require an exact document count including tombstones. Missing,
changed, wrong-version or extra documents make the job INVALID. Validation is
not a transaction with OpenSearch: the private index must have no other writers.
Job steps recheck ownership, and no normal indexer targets these private aliases.

Expose a local, one-shot command with create/step/status actions; no HTTP admin
endpoint or background rebuild at application startup. The command must require
postgres-local, disable web/publisher/indexer/search, close its context and exit.
Require an explicit loopback OpenSearch endpoint for steps. Responses contain
job metadata/counters only, no offer bodies, credentials or exception text.

Future cutover must capture Kafka partition/topic identity and starting offsets
**before** the snapshot, replay a bounded range, detect retention/partition gaps,
fence live indexer projection/offset commits during the final handoff, validate
the catch-up target and atomically switch aliases. Existing snapshot-only jobs
cannot be promoted retroactively without establishing a safe event boundary.
Never present `POST /_aliases` alone as safe online rebuilding. Interrupted
cutover reconciliation/rollback and real concurrent event tests remain required.

References:
- [Atomic alias operations](https://docs.opensearch.org/latest/api-reference/alias/aliases-api/)
- [Multi-get result/version semantics](https://docs.opensearch.org/latest/api-reference/document-apis/multi-get/)
- [PostgreSQL statement snapshots](https://www.postgresql.org/docs/17/transaction-iso.html)
