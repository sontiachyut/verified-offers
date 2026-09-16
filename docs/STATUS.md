# Current status

Last updated: 2026-09-16

## Completed

- P0: specification, invariants, APIs, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local reference model, HTTP API, Maven wrapper, CI and synthetic demonstration.
- P2: PostgreSQL 17.11, Flyway migrations, JDBC adapter and atomic transactional outbox. The in-memory adapter remains separately available.
- P3a: PostgreSQL delivery leases, transport-independent outbox relay, bounded retries, quarantine and atomic audited replay. See [delivery decisions](adr/0003-outbox-delivery.md) and [runbook](OUTBOX.md).
- P3b: acknowledged Kafka 4.2.1 adapter and opt-in Spring-managed publisher with bounded polling/I/O, result counters and graceful shutdown. Real broker pause/recovery and acknowledgement-gap replay tests pass.
- Opt-in Compose broker and [Kafka runbook](KAFKA.md) exercised end to end: API ingest → Kafka console consumption → PostgreSQL publication record, using an isolated synthetic Compose project. Three partitions, seven-day retention and loopback port bindings verified.
- P3c1: OpenSearch 3.8.0 external-version projection, retained tombstones, explicit write alias, Kafka indexing worker and durable poison quarantine. Top-N lexical search rechecks an authoritative PostgreSQL batch and fails closed on stale facts or index outages. See [ADR 0004](adr/0004-search-projection.md).
- The opt-in Compose search profile and [walkthrough](SEARCH.md) were exercised on an isolated synthetic stack: verified version 1, disabled indexer plus price-change rejection, same-group restart/catch-up to version 2, immediate authoritative deletion rejection, and three graceful API shutdowns.
- P3c2a core: durable PostgreSQL snapshot references, bounded/leased shadow rebuilding, crash-safe replay, write-blocked full-content/version/count validation. The live alias is untouched. See [ADR 0005](adr/0005-resumable-shadow-rebuild.md) and [evidence](validation/P3c2a.md).
- Packaged snapshot-only create/step/status commands resume across JVM processes and force web/publisher/indexer/search off, even when inherited flags enable them. See the [snapshot runbook](REBUILD.md). These original jobs remain nonpromotable.
- P3c2b: pre-snapshot Kafka topic/offset boundary, durable live-indexer pause, bounded source-validated replay, separately validated candidate, atomic alias handoff and forward reconciliation after a lost acknowledgement. Safe pre-switch abort is available; post-switch rollback is refused. See [ADR 0006](adr/0006-coordinated-index-handoff.md).
- Coordinated begin/step/status/abort commands work across packaged JVM restarts, including inherited-worker override protection. See the [handoff runbook](HANDOFF.md). This uses an indexing pause, not zero-downtime cutover.
- P3c3: bounded PIT/search-after pages, deterministic ordering, signed process-local cursors, current PostgreSQL verification on every page, cancellation and fixed expiry. Real index refresh/handoff/outage and packaged HTTP/restart tests pass. See [ADR 0007](adr/0007-stable-search-pages.md), [guide](PAGINATION.md) and [evidence](validation/P3c3.md).
- 49 unit/HTTP/helper plus 61 PostgreSQL/Kafka/OpenSearch/process integration tests pass locally with zero failures/errors/skips (110 total). The four-assertion HTTP demo also passes. The adapter milestone `8ba963d` separately passed 101 tests before API wiring. No benchmark or production readiness is implied.
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: P4a merchant feed jobs

P4a contract recorded in [ADR 0008](adr/0008-durable-merchant-feeds.md): bounded
NDJSON, checksum/idempotency, immutable provenance, atomic per-row receipts,
lease/retry/cancel behavior and full acceptance requirements. Implementation
is in progress; no feed feature is claimed complete by this design milestone.
Parser slice: bounded UTF-8/NDJSON admission, exact-byte SHA-256, strict schema
validation and sanitized per-line errors are implemented. `./mvnw test` passes
54 tests, including five new parser tests; full integration acceptance remains
pending the durable job engine/API slices.
Durable engine slice: V6 feed provenance/receipts, atomic catalog/outbox/row
transactions, bounded workers, lease fencing, retry pause, scoped cancellation
and audited actions now pass the full Maven gate: 124 tests (54 unit/helper,
70 integration), zero failures/errors/skips. Nine real PostgreSQL feed tests
cover admission races, rollback, takeover, parallel workers and immutable receipts.
HTTP/CLI and packaged process recovery are still the next P4a acceptance slice.
HTTP/worker slice now passes the full Maven gate: 133 tests (59 unit/helper,
74 integration), zero failures/errors/skips. Includes chunked upload bounds,
four-upload admission, packaged status/step, forced worker JVM interruption,
restart recovery and feed → Kafka → verified search. Final P4a walkthrough,
shipped example validation and handoff documentation are being completed.

The local P3 functional gate is complete, including rebuild and stable pages.
Define the bounded feed-upload and durable job contract before implementation:
input/row limits, checksum/idempotency, source provenance, per-row results,
worker ownership and restart behavior. Reuse catalog ingestion invariants and
the transactional outbox, without holding a transaction across an entire feed.
Prove partial failures, duplicate submission and crash/restart recovery with
synthetic fixtures. No arbitrary URL fetch or real merchant data. The React
search/investigation UI follows the durable feed backend in P4b.
Audited index-quarantine replay/retention remains an explicit operational gate.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Publishing, search and indexing are independently opt-in under postgres-local; indexer requires search configuration. Explicit endpoint/alias/bootstrap/group values are required. Default still accumulates outbox rows. No feed pipeline, UI or model integration yet.
- Images require security remediation/review: see [database scan](validation/IMAGE-SECURITY.md), [Kafka scan](validation/KAFKA-IMAGE-SECURITY.md) and [OpenSearch scan](validation/OPENSEARCH-IMAGE-SECURITY.md). Re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Shadow snapshots are capped at 100,000 offers and ten retained jobs; a successful handoff uses two jobs. Replay is capped at 10,000 offsets and 32 partitions. Cleanup/retention is not implemented. SNAPSHOT_VALIDATED alone is not promotion approval.
- All live indexers must run the gate-aware build. Mixed versions, foreign/transactional/compacted topic writers and manual concurrent alias administration are unsupported. A pause survives crashes; SWITCHING recovers forward, never by blind rollback. Retention loss during an uncertain switch can require reviewed repair.
- Search cursors are process-local, fixed at two minutes and invalid after restart. At most 128 reservations and eight in-flight page operations per process; completed/failed searches retain admission reservations for the expiry plus ten-second grace. This is a conservative local bound, not an HA/distributed quota or capacity result.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADRs 0003–0007.
