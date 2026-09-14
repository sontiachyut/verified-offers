# Current status

Last updated: 2026-09-14

## Completed

- P0: specification, invariants, APIs, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local reference model, HTTP API, Maven wrapper, CI and synthetic demonstration.
- P2: PostgreSQL 17.11, Flyway migrations, JDBC adapter and atomic transactional outbox. The in-memory adapter remains separately available.
- P3a: PostgreSQL delivery leases, transport-independent outbox relay, bounded retries, quarantine and atomic audited replay. See [delivery decisions](adr/0003-outbox-delivery.md) and [runbook](OUTBOX.md).
- 25 unit/HTTP/helper plus 21 PostgreSQL/process integration tests pass locally with zero failures/errors/skips. The HTTP demo also passes. See [P3a evidence](validation/P3a.md) and historical [P2 evidence](validation/P2.md).
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: P3b Kafka delivery

Implement `OutboxRelay.AcknowledgingSink` with an actual Kafka producer. Preserve
the event ID, tenant/aggregate key and schema-v1 envelope. Select and pin/scan the
broker image, add Testcontainers broker acknowledgement/outage/replay tests,
then wire an opt-in local scheduler with bounded polling and clean shutdown.
Keep send/ack deadlines below the 30-second lease; do not mark success when merely
queueing an async send. Follow ADR 0003. P3c remains OpenSearch version ordering,
tombstones, rebuild/catch-up and authoritative search verification.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- The application still accumulates outbox rows: the tested relay has no live broker adapter or background scheduler yet. No search index, feed pipeline or model integration yet. P3a completion does not complete P3.
- The database image has known vulnerability findings: see [image security record](validation/IMAGE-SECURITY.md). Remediation/re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADR 0003.
