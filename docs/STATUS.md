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
- 36 unit/HTTP/helper plus 45 PostgreSQL/Kafka/OpenSearch/process integration tests pass locally with zero failures/errors/skips (81 total). See [rebuild evidence](validation/P3c2a.md), [search evidence](validation/P3c1.md) and [P3b evidence](validation/P3b.md).
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: operator command, then P3c2b catch-up/cutover

Expose the tested shadow engine through a guarded one-shot create/step/status
command and validate the packaged process. Then capture Kafka topic identity and
start offsets BEFORE a new snapshot, replay a bounded catch-up range, fence live
indexer writes/commits, validate and atomically switch the write/search alias.
Existing snapshot-only jobs are never promotable. Test concurrent updates,
deletions, retention gaps, interrupted cutover and rollback. P3 stays open.
PIT pagination and audited index-quarantine replay/retention also remain open.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Publishing, search and indexing are independently opt-in under postgres-local; indexer requires search configuration. Explicit endpoint/alias/bootstrap/group values are required. Default still accumulates outbox rows. No feed pipeline, UI or model integration yet. P3c1 completion does not complete P3.
- Images require security remediation/review: see [database scan](validation/IMAGE-SECURITY.md), [Kafka scan](validation/KAFKA-IMAGE-SECURITY.md) and [OpenSearch scan](validation/OPENSEARCH-IMAGE-SECURITY.md). Re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Shadow snapshots are capped at 100,000 offers and ten retained jobs; cleanup/retention is not implemented. SNAPSHOT_VALIDATED is not a promotion approval.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADRs 0003–0005.
