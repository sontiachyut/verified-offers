# Verified Offers

Check whether a merchant offer is current and supported by its source facts.

[![verify](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml)

A source-verification system with an evidence-first investigation console. **Durable merchant feeds flow through PostgreSQL and Kafka into search, which rechecks current source facts before returning verified offers.** This is a local development system, not a production deployment or a large-scale performance claim.

## Run it

Requirements: Java 21 and a running Docker daemon. Node.js 22+ and make are needed for the scripted HTTP demo. The Maven wrapper downloads a checksum-pinned distribution.

```sh
./mvnw verify
make demo
```

Verification starts isolated PostgreSQL, Kafka and OpenSearch containers and tests the packaged API. The final HTTP walkthrough uses fresh in-memory state on a dynamic loopback port. Tests clean up their own containers/processes; no paid services or external merchant data are used.

For persistent exploration, follow the [PostgreSQL setup](docs/POSTGRES.md). For the volatile reference implementation:

```sh
make run
# localhost:8081; local-demo profile, state lost at shutdown
```

For the full current flow—API → PostgreSQL outbox → Kafka—follow the
[local Kafka walkthrough](docs/KAFKA.md). It includes topic creation, an actual
consumer, outage/recovery and shutdown commands. The Compose `messaging` profile
starts a single local broker only when requested.

For the complete search flow, follow the [OpenSearch walkthrough](docs/SEARCH.md).
It covers explicit alias provisioning, verified results, pausing/resuming the
indexer and graceful shutdown. The separate Compose `search` profile keeps the
index off unless requested.

The [shadow-rebuild runbook](docs/REBUILD.md) provides snapshot-only commands.
The [coordinated handoff runbook](docs/HANDOFF.md) adds bounded Kafka catch-up,
full candidate validation and recoverable alias switching. It explicitly pauses
indexing during the handoff; it is not a zero-downtime claim.

The [merchant-feed walkthrough](docs/FEEDS.md) covers bounded NDJSON uploads,
durable progress, per-row receipts, retry/cancellation and restart recovery.
It includes a [mixed synthetic example](examples/feed-mixed.ndjson) exercised
against the packaged API. Feed ingestion and its worker are opt-in separately.

Without Docker, `./mvnw test` runs only unit/in-memory HTTP tests—not the full acceptance gate.

### Investigation console

The [React/TypeScript console](docs/CONSOLE.md) provides verified search, source/index
evidence, bounded feed uploads, durable row receipts and explicit recovery actions.
It uses the existing API; no browser-side verification or fabricated fallback data.

```sh
cd console
# Node 22.22.2; scoped npm version avoids changing your global installation
npx --yes npm@11.11.0 ci --no-fund
npm run dev
```

Open the printed loopback URL. Start the search/feed-enabled backend using the
[console runbook](docs/CONSOLE.md) for real data. Frontend checks (`npm run check`)
need no Docker. The isolated `npm run test:integration` walkthrough requires the
packaged Java jar and Docker; it cleans up only its synthetic test stack.

## What works today

- Versioned offer ingestion with identical replay, conflicting/older-version rejection and deterministic claim verification.
- PostgreSQL facts, immutable version history and transactional outbox; Flyway migrations.
- Durable feed jobs with exact-byte checksum/idempotency, immutable source provenance and bounded row reports. Each successful row's catalog update, outbox event and receipt commit together.
- Lease-fenced feed workers, bounded batches/retries, operator-audited retry/cancellation and forced-process restart recovery. Bad rows do not block valid rows; cancelled work does not undo committed offers.
- Concurrent first-write serialization, tenant-key separation, timestamp normalization and rollback on outbox failure.
- Packaged API restart recovery: committed offers survive a forced JVM stop without duplicate events.
- An outbox relay with worker leases, expiry fencing, bounded retries, quarantine and audited replay.
- Opt-in background publishing to Kafka, with bounded I/O, graceful shutdown and per-result counters.
- Replay-safe OpenSearch indexing with external versions, retained tombstones, durable poison-event quarantine and manual Kafka offset commits.
- Tenant-filtered lexical search with bounded PostgreSQL batch verification, as-of provenance and stale-candidate rejection on every page.
- Stable PIT/search-after pagination through refreshes and alias handoffs, with signed short-lived cursors, cancellation and bounded resource admission. Cursors are process-local, not HA state. See the [pagination guide](docs/PAGINATION.md).
- Local React console with lossless money/version evidence, explicit snapshot expiry, empty-page continuation, exact-byte upload retries and scoped feed investigation. Native forms/dialogs, visible keyboard focus and responsive layouts; no background polling or invented dashboard metrics.
- A resumable shadow-rebuild engine: durable PostgreSQL snapshots, fenced leases, bounded batches and full-content/version/count validation. Validated shadows stay read-only and never replace the live index automatically.
- Coordinated rebuild with topic-identity/retention checks, source-validated Kafka catch-up, durable indexing pause and atomic alias handoff. Interrupted switches reconcile forward; unsafe rollback is refused.
- 134 passing tests, including actual database/broker/index integration, atomic feed rollback, forced worker restart, full feed-to-search delivery, stable pagination and interrupted index handoff recovery. See [feed evidence](docs/validation/P4a.md), [pagination evidence](docs/validation/P3c3.md) and [handoff evidence](docs/validation/P3c2b.md).
- 38 console tests plus a real-API React walkthrough covering mixed feed receipts, idempotent recovery, verified pagination and source-deletion exclusion. See [console acceptance evidence and visual-review limits](docs/validation/P4b.md).

A verified response is an as-of fact check, **not a stock reservation or checkout-price guarantee**.

## Next phases — not yet implemented

Optional Python claim extraction follows an independently evaluated deterministic baseline. Real-browser visual/accessibility review remains separate from automated DOM checks and the real-API React walkthrough.

Publishing, indexing and search are disabled unless explicitly enabled under the persistent local profile. Authentication, audited index-quarantine replay, operational hardening, backup/restore and representative load measurements remain open. No cloud resources have been provisioned. The pinned [database](docs/validation/IMAGE-SECURITY.md), [Kafka](docs/validation/KAFKA-IMAGE-SECURITY.md) and [OpenSearch](docs/validation/OPENSEARCH-IMAGE-SECURITY.md) images require security review; public deployment is not approved.

## Engineering documents

- [Specification: invariants, API, data model, security and scale targets](docs/SPEC.md)
- [Phased roadmap and acceptance gates](docs/ROADMAP.md)
- [Current status and precise next task](docs/STATUS.md)
- [Architecture boundaries](docs/adr/0001-boundaries-and-proof.md) and [PostgreSQL transaction decisions](docs/adr/0002-postgres-transactions.md)
- [Reference demo](docs/DEMO.md) and [persistent local profile](docs/POSTGRES.md)
- [Outbox recovery walkthrough and runbook](docs/OUTBOX.md) and [delivery decisions](docs/adr/0003-outbox-delivery.md)
- [Search projection decisions](docs/adr/0004-search-projection.md) and [validation](docs/validation/P3c1.md)
- [P2 validation evidence](docs/validation/P2.md) and [historical P1 record](docs/validation/P1.md)
- [Optional companion-project integration](docs/INTEGRATION.md)
- [Investigation console runbook](docs/CONSOLE.md) and [client-boundary decisions](docs/adr/0009-investigation-console.md)

The companion project is [Inventory Reservation & Fulfillment](https://github.com/sontiachyut/inventory-fulfillment). Each repository runs independently.

## Evidence before claims

Database contention and restart tests establish specific correctness properties under synthetic fixtures. They do not establish throughput, latency SLOs, database disaster recovery or production availability. Capacity numbers in the spec are proposed workloads, not achieved performance.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
