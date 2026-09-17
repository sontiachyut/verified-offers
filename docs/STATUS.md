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
- P4a: bounded UTF-8 NDJSON admission with exact-byte checksum/idempotency, immutable provenance, durable per-row results and atomic catalog/outbox/receipt commits. Fenced workers, retry pause, scoped audited retry/cancel, HTTP/CLI progress and forced-process restart recovery are tested. See [ADR 0008](adr/0008-durable-merchant-feeds.md), [walkthrough](FEEDS.md) and [evidence](validation/P4a.md).
- The mixed synthetic example is tested through the packaged API; old source facts remain stale. The complete feed → PostgreSQL outbox → Kafka → verified search flow passes against real dependencies. Feed completion itself does not promise downstream search visibility.
- 59 unit/HTTP/helper plus 75 PostgreSQL/Kafka/OpenSearch/process integration tests pass locally with zero failures/errors/skips (134 total). The four-assertion HTTP demo also passes. Feed milestones separately passed 54 unit, 124 full and 133 full tests before the final example gate. No benchmark or production readiness is implied.
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: P4b search/feed investigation UI

P4a backend acceptance is complete; the combined P4 phase remains open for its UI.
Define a small accessible React/TypeScript console around existing contracts:
verified search with provenance/as-of evidence, cursor expiry/continuation and
empty-page behavior; bounded feed upload; job/row progress and fixed errors;
explicit retry/cancel confirmations and visible partial-commit semantics.
Keep tenant scope labeled as unauthenticated local demo scope. Do not create a
second authority for verification in the browser. Test loading/empty/error states,
keyboard accessibility and an end-to-end synthetic walkthrough. No public hosting
or real data without the existing security/deployment approval gates.
Audited index-quarantine replay/retention remains an open operational gate.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Publishing, search, indexing and feeds are independently opt-in under postgres-local; indexer requires search configuration. Explicit endpoint/alias/bootstrap/group values are required. Feed background processing has its own enable flag. No UI or model integration yet.
- Images require security remediation/review: see [database scan](validation/IMAGE-SECURITY.md), [Kafka scan](validation/KAFKA-IMAGE-SECURITY.md) and [OpenSearch scan](validation/OPENSEARCH-IMAGE-SECURITY.md). Re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Shadow snapshots are capped at 100,000 offers and ten retained jobs; a successful handoff uses two jobs. Replay is capped at 10,000 offsets and 32 partitions. Cleanup/retention is not implemented. SNAPSHOT_VALIDATED alone is not promotion approval.
- All live indexers must run the gate-aware build. Mixed versions, foreign/transactional/compacted topic writers and manual concurrent alias administration are unsupported. A pause survives crashes; SWITCHING recovers forward, never by blind rollback. Retention loss during an uncertain switch can require reviewed repair.
- Search cursors are process-local, fixed at two minutes and invalid after restart. At most 128 reservations and eight in-flight page operations per process; completed/failed searches retain admission reservations for the expiry plus ten-second grace. This is a conservative local bound, not an HA/distributed quota or capacity result.
- Feeds are bounded at 1 MiB/1,000 rows/4 KiB per line, four concurrent uploads per process, 100 retained jobs globally and 20 operator actions per job. Immutable provenance/receipts are retained; no cleanup endpoint exists. Retry/cancel are unauthenticated local-operator actions, not an auth audit. Cancellation never undoes committed rows.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADRs 0003–0008.
