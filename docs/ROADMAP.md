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

P2 functional gate completed: see [transaction decisions](adr/0002-postgres-transactions.md) and [validation](validation/P2.md). Deployment security remains open.

P3a completed: database lease/retry/quarantine/replay and relay failure tests.
See [validation](validation/P3a.md). P3b is now implemented and tested with
real broker acknowledgement, outage/recovery, replay and packaged API shutdown;
see [Kafka validation](validation/P3b.md). P3c1 now adds tested indexing and
authoritatively verified search; see [search evidence](validation/P3c1.md).
P3c2a now provides a resumable, validated shadow snapshot with no live alias
changes; see [rebuild evidence](validation/P3c2a.md). P3c2b now adds bounded Kafka
catch-up and a coordinated, recoverable alias switch with an indexing pause;
see [handoff evidence](validation/P3c2b.md). P3c3 now adds bounded stable PIT pages
with current source verification; see [pagination evidence](validation/P3c3.md).
The local P3 functional gate is complete. P4a durable merchant feed jobs are now
complete: bounded admission, atomic row receipts, restart recovery and the tested
feed-to-search pipeline; see [feed evidence](validation/P4a.md).
P4b's local functional gate now passes: React search/feed investigation,
38 focused interaction/DOM tests and a real-API UI walkthrough; see
[console evidence](validation/P4b.md). P4 local functionality is implemented.
A connected-browser visual/native-interaction review remains explicitly open;
automated DOM tests are not a substitute for that review.
Audited index-quarantine source reconciliation is now implemented; arbitrary
malformed-event replay is not claimed. Evidence is retained with explicit local
admission caps; destructive lifecycle automation remains future design work.

P5 now has signed-token tenant/merchant security tests and published baseline
ranking/extraction measurements, including errors. Model integration remains
optional and unimplemented. P6 now includes a bounded mixed-load observation,
independent-database restore, tested runtime roles, protected metrics and non-root
packaging. Representative load/freshness, patched production images, full browser
certification, HA and deployed telemetry remain open; these are not silently
replaced by the small local measurements. See [COMPLETION.md](COMPLETION.md).

P3 is split into acceptance slices: P3a database-backed relay and failure recovery
([ADR 0003](adr/0003-outbox-delivery.md)); P3b actual Kafka delivery; P3c OpenSearch
projection/rebuild and authoritative search verification. Completion of P3a does
not complete the P3 phase.
