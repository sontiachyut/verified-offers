# P3c2a validation: resumable shadow snapshots

Date: 2026-09-16. P3 remains open; no live index cutover is implemented.

Core gate: `./mvnw --batch-mode --no-transfer-progress verify` passed **81 tests**
(36 unit/HTTP/helper, 45 PostgreSQL/Kafka/OpenSearch/process integration), zero
failures/errors/skips. Uses Java 21 on the local Apple Silicon machine and the
existing digest-pinned PostgreSQL 17.11, Kafka 4.2.1 and OpenSearch 3.8.0 images.
No new image or dependency was introduced. Existing scan findings still block
public deployment; correctness tests are not security or capacity certification.

Final operator/bulk-response gate: the same full command passed **86 tests**
(39 unit/HTTP/helper + 47 integration), zero failures/errors/skips. The core
commit `df6b411` also passed GitHub Actions; check the exact final operator commit
separately rather than treating this historical result as its CI outcome.

## Evidence

- Durable snapshot references preserve exact original versions and tombstones
  after source updates, deletions and new identities. SQL rejects mutation of
  snapshot rows. No network work holds a database transaction.
- A lower configured snapshot cap rolls back both job and references, leaving
  catalog data untouched. Ten retained jobs are allowed; the eleventh fails.
  This tests cap enforcement, not a 100,000-offer performance benchmark.
- Two synchronized workers compete for a job; exactly one obtains a lease.
  After expiry/reclaim, the old owner cannot checkpoint or release the new lease.
- 103 source offers require two keyset batches (100 + 3). A newly constructed
  store resumes from the persisted ordinal. An index outage releases ownership
  with a fixed code and no progress; the same job succeeds after recovery.
- Real OpenSearch bulk acknowledgement followed by an injected worker crash
  leaves the database checkpoint unchanged. After lease expiry, a replacement
  worker safely replays and validates without duplicate documents.
- Read every snapshot back through real-time multi-get; compare full facts and
  external versions. Separately injected missing documents, changed titles,
  wrong engine versions and extra documents all produce INVALID, never success.
- Refuse an existing index without job metadata and one with an unexpected
  additional alias. The live alias remains attached to its original index and
  serves its original document throughout shadow construction/validation.
- Validated shadows remain write-blocked. Empty snapshots validate correctly.
  Terminal jobs do not restart or perform an alias switch.
- Packaged operator JVMs create a snapshot, resume one page per process, reach
  SNAPSHOT_VALIDATED and read status, then exit without forced termination.
  Inherited publisher/indexer/search flags are deliberately true; the operator
  overrides them, starts no web server and leaves outbox records unpublished.
  Unsafe/remote arguments and an oversized snapshot exit nonzero without jobs.
- A controlled HTTP bulk response reports one success and one item error: the
  adapter rejects the batch rather than trusting HTTP 200. Equal-version replay
  conflicts are accepted; unrelated conflicts are rejected without leaking the
  fixture's error detail.

The existing four-assertion HTTP reference demo also passes. No independent
manual Compose rebuild walkthrough is claimed; the documented create/step/status
sequence is exercised by packaged-process integration tests against actual
PostgreSQL and OpenSearch containers.

## Boundaries

Snapshot validation is not online rebuild completion. These jobs have no
pre-snapshot Kafka boundary and are never promotable. Capturing topic identity
and offsets before a new snapshot, bounded catch-up, live indexer fencing,
atomic alias handoff and interrupted-cutover recovery are next. No multi-node
failover, throughput, recovery-time or zero-downtime claim follows from this run.

Snapshots retain references to existing immutable history; retention/cleanup is
not implemented. The 100,000-offer maximum and ten-job guard bound local work,
not overall database/index size. An OpenSearch administrator can still bypass
the write block or edit ownership metadata; local mode is not an auth boundary.
