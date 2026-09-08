# Verified Offers

Check whether a merchant offer is current and supported by its source facts.

[![verify](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml)

An incremental backend engineering project. **P2 adds PostgreSQL persistence and transactional integration tests.** This is a local development system, not a production deployment or a large-scale performance claim.

## Run it

Requirements: Java 21 and a running Docker daemon. Node.js 22+ and make are needed for the scripted HTTP demo. The Maven wrapper downloads a checksum-pinned distribution.

```sh
./mvnw verify
make demo
```

Verification starts isolated PostgreSQL containers and tests the packaged API. The final HTTP walkthrough uses fresh in-memory state on a dynamic loopback port. Tests clean up their own containers/processes; no paid services or external merchant data are used.

For persistent exploration, follow the [PostgreSQL setup](docs/POSTGRES.md). For the volatile reference implementation:

```sh
make run
# localhost:8081; local-demo profile, state lost at shutdown
```

Without Docker, `./mvnw test` runs only unit/in-memory HTTP tests—not the full acceptance gate.

## What works today

- Versioned offer ingestion with identical replay, conflicting/older-version rejection and deterministic claim verification.
- PostgreSQL facts, immutable version history and transactional outbox; Flyway migrations.
- Concurrent first-write serialization, tenant-key separation, timestamp normalization and rollback on outbox failure.
- Packaged API restart recovery: committed offers survive a forced JVM stop without duplicate events.

A verified response is an as-of fact check, **not a stock reservation or checkout-price guarantee**.

## Next phases — not yet implemented

Kafka outbox publication, OpenSearch retrieval, merchant feed jobs and a React investigation console. Search will recheck authoritative facts. Optional Python claim extraction follows an independently evaluated deterministic baseline.

The outbox is persisted but **not published yet**. Authentication, operational hardening, backup/restore and representative load measurements remain open. No cloud resources have been provisioned. The pinned database image has [known security findings](docs/validation/IMAGE-SECURITY.md); public deployment is not approved.

## Engineering documents

- [Specification: invariants, API, data model, security and scale targets](docs/SPEC.md)
- [Phased roadmap and acceptance gates](docs/ROADMAP.md)
- [Current status and precise next task](docs/STATUS.md)
- [Architecture boundaries](docs/adr/0001-boundaries-and-proof.md) and [PostgreSQL transaction decisions](docs/adr/0002-postgres-transactions.md)
- [Reference demo](docs/DEMO.md) and [persistent local profile](docs/POSTGRES.md)
- [P2 validation evidence](docs/validation/P2.md) and [historical P1 record](docs/validation/P1.md)
- [Optional companion-project integration](docs/INTEGRATION.md)

The companion project is [Inventory Reservation & Fulfillment](https://github.com/sontiachyut/inventory-fulfillment). Each repository runs independently.

## Evidence before claims

Database contention and restart tests establish specific correctness properties under synthetic fixtures. They do not establish throughput, latency SLOs, database disaster recovery or production availability. Capacity numbers in the spec are proposed workloads, not achieved performance.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
