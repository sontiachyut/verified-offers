# Verified Offers

Check whether a merchant offer is current and supported by its source facts.

[![verify](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml/badge.svg)](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml)

This is an incremental backend engineering project. **P1 is a runnable, local-only reference implementation.** It uses bounded in-memory state: restarting loses all data. It is not production-ready and has no measured distributed-scale results.

## Run it

Requirements: Java 21. Node.js 22+ and make are needed for the scripted demo. The Maven wrapper downloads a checksum-pinned Maven distribution.

```sh
./mvnw verify
make demo
```

The demo packages and starts a fresh application on a dynamic loopback port, runs HTTP assertions, then stops that process. No Docker, paid services, external merchant data or credentials are needed.

For manual API exploration:

```sh
make run
# localhost:8081; local-demo profile, volatile state
```

## What works today

- Versioned offer ingestion with idempotent identical replay and explicit conflicting/older-version rejection.
- Structured price/stock verification with source timestamps and versions.
- Freshness boundaries, tombstones and explicit NOT_FOUND / DELETED / STALE / MISMATCH / UNAVAILABLE / VERIFIED outcomes.
- Strict input validation, bounded demo state, REST endpoints and unit/HTTP tests.

A verified response is an as-of fact check, **not a stock reservation or checkout-price guarantee**.

## Planned architecture — not yet implemented

Java/Spring Boot API → PostgreSQL facts/history/outbox → Kafka → OpenSearch indexer; search rechecks authoritative facts before presenting verified claims. React provides the product console. Optional Python claim extraction comes after an independently evaluated deterministic baseline.

PostgreSQL persistence, full-text search, feed jobs, Kafka, model integration and the UI are future phases.
No cloud resources have been provisioned.

## Engineering documents

- [Specification: invariants, API, data model, security and scale targets](docs/SPEC.md)
- [Phased roadmap and acceptance gates](docs/ROADMAP.md)
- [Current status and precise next task](docs/STATUS.md)
- [Architecture decision](docs/adr/0001-boundaries-and-proof.md)
- [Demo walkthrough](docs/DEMO.md)
- [Optional companion-project integration](docs/INTEGRATION.md)
- [Validation record](docs/validation/P1.md)

The companion project is [Inventory Reservation & Fulfillment](https://github.com/sontiachyut/inventory-fulfillment). Each repository runs independently.

## Evidence before claims

The roadmap includes database concurrency tests, event replay, failure injection, load tests and restore drills. Capacity numbers in the spec are **proposed workloads**, not achieved performance. Results will report hardware, configuration, errors and limitations.

See [CONTRIBUTING](CONTRIBUTING.md) for the AI-assisted development/review workflow and [SECURITY](SECURITY.md) for deployment restrictions.
