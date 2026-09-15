# Current status

Last updated: 2026-09-14

## Completed

- P0: specification, invariants, APIs, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local reference model, HTTP API, Maven wrapper, CI and synthetic demonstration.
- P2: PostgreSQL 17.11, Flyway migrations, JDBC adapter and atomic transactional outbox. The in-memory adapter remains separately available.
- P3a: PostgreSQL delivery leases, transport-independent outbox relay, bounded retries, quarantine and atomic audited replay. See [delivery decisions](adr/0003-outbox-delivery.md) and [runbook](OUTBOX.md).
- P3b: acknowledged Kafka 4.2.1 adapter and opt-in Spring-managed publisher with bounded polling/I/O, result counters and graceful shutdown. Real broker pause/recovery and acknowledgement-gap replay tests pass.
- 29 unit/HTTP/helper plus 26 PostgreSQL/Kafka/process integration tests pass locally with zero failures/errors/skips (55 total). The HTTP demo also passes. See [P3b evidence](validation/P3b.md) and historical [P3a evidence](validation/P3a.md).
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: finish local delivery runbook, then P3c retrieval

Finish the opt-in Compose broker and local runbook, including topic creation and
safe shutdown. Then implement P3c: an OpenSearch consumer with monotonic external
version updates and retained tombstones; duplicate/out-of-order/rebuild tests;
and search results rechecked against current PostgreSQL facts. Pin/scan the index
image before introduction. Follow ADR 0003 and keep P3 open until all retrieval
acceptance gates pass.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Publishing requires `offers.publisher.enabled=true` and an explicit bootstrap address under postgres-local. The default still accumulates outbox rows. No search index, feed pipeline or model integration yet. P3b completion does not complete P3.
- The database and Kafka images have known vulnerability findings: see [database scan](validation/IMAGE-SECURITY.md) and [Kafka scan](validation/KAFKA-IMAGE-SECURITY.md). Remediation/re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADR 0003.
