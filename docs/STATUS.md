# Current status

Last updated: 2026-09-08

## Completed

- P0: specification, invariants, APIs, security gates, capacity methodology and acceptance-gated roadmap.
- P1: Java 21/Spring Boot 4.1.1 local reference model, HTTP API, Maven wrapper, CI and synthetic demonstration.
- P2: PostgreSQL 17.11, Flyway migrations, JDBC adapter and atomic transactional outbox. The in-memory adapter remains separately available.
- 25 unit/HTTP/helper plus 8 PostgreSQL/process integration tests pass locally with zero failures/errors/skips. Docker is now running and database tests actually executed. See [P2 evidence](validation/P2.md).
- Concurrent ingestion, immutable history, replay/conflicts, rollback and forced-process restart recovery tested.
- CI runs the same full Maven acceptance gate, then the HTTP walkthrough. Check its result against the exact pushed main revision, not Dependabot branches.

## Exact next task: P3 event delivery and retrieval

Write the outbox publication/index-version ADR and failure-oriented Kafka/OpenSearch tests before implementing workers. Define publisher claim/lease recovery, at-least-once publication, duplicate handling, tombstone/version ordering and index rebuild. Search must recheck authoritative PostgreSQL facts. Start with crash-after-publish/before-marking-delivered tests.

## Explicit limits / open decisions

- postgres-local persists state; local-demo remains volatile. Both profiles are unauthenticated and loopback-only. No real data or public exposure.
- Outbox rows accumulate but are not published. No search index, feed pipeline or model integration yet.
- The database image has known vulnerability findings: see [image security record](validation/IMAGE-SECURITY.md). Remediation/re-scan, production database roles, auth, restore drills and cloud sizing/cost remain deployment gates.
- Correctness tests are not throughput, uptime, failover or representative scale measurements.
- No billable cloud resources were created. The seven-session sprint is a planning aid, not a completeness promise.
- Future work is not automatically scheduled. Resume from this file, ROADMAP.md and ADR 0002.
