# Verified Offers

Check whether a merchant offer is current and supported by its source facts.

[![verify](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml)

An incremental backend engineering project. **Kafka now feeds an OpenSearch projection, and search rechecks current PostgreSQL facts before returning verified offers.** This is a local development system, not a production deployment or a large-scale performance claim.

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

The [shadow-rebuild runbook](docs/REBUILD.md) adds one-shot create/step/status
commands with durable progress and full validation. It leaves the live index
untouched; Kafka catch-up and promotion are not implemented yet.

Without Docker, `./mvnw test` runs only unit/in-memory HTTP tests—not the full acceptance gate.

## What works today

- Versioned offer ingestion with identical replay, conflicting/older-version rejection and deterministic claim verification.
- PostgreSQL facts, immutable version history and transactional outbox; Flyway migrations.
- Concurrent first-write serialization, tenant-key separation, timestamp normalization and rollback on outbox failure.
- Packaged API restart recovery: committed offers survive a forced JVM stop without duplicate events.
- An outbox relay with worker leases, expiry fencing, bounded retries, quarantine and audited replay.
- Opt-in background publishing to Kafka, with bounded I/O, graceful shutdown and per-result counters.
- Replay-safe OpenSearch indexing with external versions, retained tombstones, durable poison-event quarantine and manual Kafka offset commits.
- Tenant-filtered lexical search with one bounded PostgreSQL batch verification, as-of provenance and stale-candidate rejection.
- A resumable shadow-rebuild engine: durable PostgreSQL snapshots, fenced leases, bounded batches and full-content/version/count validation. Validated shadows stay read-only and never replace the live index automatically.
- 86 passing tests, including actual database/broker/index integration, rebuild crash recovery, corruption rejection, packaged operator commands and index-outage HTTP 503 behavior. See [rebuild evidence](docs/validation/P3c2a.md) and [search evidence](docs/validation/P3c1.md).

A verified response is an as-of fact check, **not a stock reservation or checkout-price guarantee**.

## Next phases — not yet implemented

Online index rebuild/catch-up/alias switching, stable pagination, merchant feed jobs and a React investigation console. Optional Python claim extraction follows an independently evaluated deterministic baseline.

Publishing, indexing and search are disabled unless explicitly enabled under the persistent local profile. P3c1 delivers top-N search, not the full P3 rebuild gate. Authentication, audited index-quarantine replay, operational hardening, backup/restore and representative load measurements remain open. No cloud resources have been provisioned. The pinned [database](docs/validation/IMAGE-SECURITY.md), [Kafka](docs/validation/KAFKA-IMAGE-SECURITY.md) and [OpenSearch](docs/validation/OPENSEARCH-IMAGE-SECURITY.md) images require security review; public deployment is not approved.

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

The companion project is [Inventory Reservation & Fulfillment](https://github.com/sontiachyut/inventory-fulfillment). Each repository runs independently.

## Evidence before claims

Database contention and restart tests establish specific correctness properties under synthetic fixtures. They do not establish throughput, latency SLOs, database disaster recovery or production availability. Capacity numbers in the spec are proposed workloads, not achieved performance.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
