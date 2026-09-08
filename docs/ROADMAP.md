# Roadmap

Phases are acceptance-gated. Sessions can span multiple days; dates follow actual work.

| Phase | Deliverable | Exit evidence |
|---|---|---|
| P0 | Spec, ADRs, roadmap, continuity files | Complete invariants/API/scale/security plan reviewed |
| P1 | Local catalog + deterministic verifier; Java REST and CI | Unit/HTTP tests pass, demo runs; no persistence/search claims |
| P2 | PostgreSQL/Flyway catalog/history/outbox | Testcontainers duplicate/conflict/concurrent-upsert/restart/rollback tests, database constraints |
| P3 | Kafka publisher + OpenSearch retrieval | Rebuild, tombstone/out-of-order/crash/replay tests; stale candidates fail authoritative verification |
| P4 | Merchant feed jobs + React search/investigation UI | Bounded uploads, progress/error views, source provenance, accessible walkthrough |
| P5 | Evaluated claim extraction/ranking and auth hardening | Independent labeled set, lexical baseline, cost/quality report; tenant/security tests |
| P6 | Load/failure experiments and operational packaging | Raw reproducible results, restore drill, runbooks, scanned/pinned images, limitations |

Initial seven-session sprint: 1 specs/P1; 2 PostgreSQL; 3 concurrency + outbox; 4 publisher/indexer; 5 search + freshness tests; 6 thin UI; 7 recovery demo/docs. P5/P6 can extend beyond the sprint. Do not call the full specification complete after P1.

First P2 task: write a persistence ADR and PostgreSQL integration tests for concurrent first insert, identical replay, conflicting same-version payload and lower-version replay before implementing the JDBC adapter.
