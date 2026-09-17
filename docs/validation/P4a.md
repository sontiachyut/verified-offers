# P4a validation: durable merchant-feed ingestion

Date: 2026-09-16. Full acceptance command:
`./mvnw --batch-mode --no-transfer-progress verify`.

Milestones: parser `5c3c7c3` passed 54 unit/helper tests; durable engine `c2c4140`
passed 124 total tests; API/worker/pipeline `ca9894a` passed 133 total tests.
The final gate including the shipped example passes **134 tests**: 59
unit/HTTP/helper + 75 PostgreSQL/Kafka/OpenSearch/process integration tests,
zero failures, errors or skips. The four-assertion `node scripts/demo.mjs`
reference walkthrough also passes. Check exact-revision GitHub CI separately.

Local Java 21/Apple Silicon and existing pinned PostgreSQL 17.11, Kafka 4.2.1,
OpenSearch 3.8.0 images. No new library, image, external data or cloud service.
No throughput/HA/security certification is inferred from these fixture tests.

## Input and admission

Five parser tests cover UTF-8/checksum rejection; NDJSON final newline/CRLF;
exact row/line caps and first-excess rejection; invalid/missing/unknown/null
fields; scalar coercion, duplicate JSON keys, trailing data, future source time
and tenant mismatch. Invalid rows retain only fixed error codes and line hashes.
Raw malformed content is absent from parsed/storage results.

Four concurrent blocking uploads hold the admission permits; a fifth gets 429
without reading its body. Packaged HTTP tests exercise both Content-Length and
chunked >1 MiB requests, returning 413 without another job. Unsupported encoding,
checksum conflict and scoped idempotency conflicts also fail before mutation.

Twenty concurrent submissions of identical content/key converge on one job and
one row set; only one reports created. Different content/source with the same
key returns conflict. A full 100-job budget rejects new keys but still permits
idempotent lookup of existing jobs. Entirely invalid feeds complete as errors
without claiming a worker or creating outbox events.

## Transaction and worker correctness

Nine real PostgreSQL tests cover:

- Input-order APPLIED/REPLAYED/REJECTED receipts, including a lower version after
  a higher one, with exact counters and no duplicate outbox events.
- Independently injected outbox and receipt constraint failures roll back the
  catalog/history, outbox, row receipt and job checkpoint together. The pending
  row succeeds after repair; storage failure is never relabeled as a bad row.
- An expired/reclaimed worker cannot checkpoint or release the replacement's
  lease. Already committed receipts survive the takeover.
- A 53-row job spans 25-row batches; eight competing workers finish exactly 53
  durable updates/outbox events without duplicates.
- Five consecutive failures pause the job with fixed diagnostics. Explicit
  audited retry resets failure state and resumes pending rows.
- Cancellation preserves one already committed row, cancels only pending rows,
  and fences the original worker. It does not undo catalog/outbox effects.
- Database triggers reject provenance/terminal-receipt edits and deletion.
  Row/job pagination is bounded; wrong tenant scope returns 404.

Separate poller tests verify that a database exception does not cancel future
polls and an interrupted poller does not claim another job. CLI parser tests
reject worker overrides, mixed commands, incomplete scope and duplicate options.

## Packaged processes and complete pipeline

Four FeedApiIT tests exercise actual packaged JVMs:

- HTTP admission/replay, scope isolation, bounded progress/row pages, cancellation
  and action audit. With background processing disabled, accepted jobs remain
  queued and outbox rows are not created by admission itself.
- Hold the second offer identity lock while the first row commits, forcibly kill
  the worker JVM, then restart. The first receipt persists; the remaining two
  rows complete, with exactly three history/outbox rows. **Lease expiry is advanced
  in the isolated fixture** to avoid waiting 60 seconds; this is a real process
  kill/restart test, not a power-loss or elapsed recovery-time measurement.
- One-shot status/step JVMs override inherited feed/publisher/indexer/search flags,
  process at most 25 rows, resume the remaining rows and exit themselves. Outbox
  remains unpublished without the independent publisher.
- Upload the actual [mixed example](../../examples/feed-mixed.ndjson), matching
  the runbook's two APPLIED, one REPLAYED and one REJECTED outcomes. Reupload
  returns the same job. Historical source facts remain STALE after ingestion.

OpenSearchIT additionally runs the complete packaged feed worker + outbox
publisher + Kafka indexer + search API. A mixed feed completes with one rejected
row and one applied offer; that offer becomes VERIFIED with its original source
provenance. Reupload does not create another publication or receipt.
Existing rebuild operator tests also inherit feed-enabled flags; commands force
all background processing off in their own process.

## Completion boundary

P4a's local merchant-feed backend acceptance gate is complete. No independent
manual Compose walkthrough is claimed: packaged-process integration tests cover
the runbook's HTTP/CLI behavior and exact shipped fixture. P4b's React UI remains
next; P4 as a whole is not complete.

Limits remain explicit: 1 MiB/1,000 rows/4 KiB per line; 100 retained jobs; 20
operator actions/job; synthetic unauthenticated local mode. The feed storage cap
does not bound all catalog/history/index storage. There is no cleanup/retention
workflow, verified operator identity, billable deployment, benchmark or promise
of immediate Kafka/search visibility when ingestion completes. Source versions
still arbitrate conflicts across independent feeds and direct API writes.

See [ADR 0008](../adr/0008-durable-merchant-feeds.md) and
[operator walkthrough](../FEEDS.md). Image remediation, auth/roles, restore drills,
representative load and quarantine retention/replay remain deployment gates.
