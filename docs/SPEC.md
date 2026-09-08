# Verified Offers — product and engineering specification

Status: approved project direction; implementation progresses only through acceptance gates.
P2 clarification: ADR 0002 defines implemented persistence, locking, timestamp and local-profile semantics and supersedes preliminary P2 design details below. Authentication remains a predeployment gate; postgres-local is not a public production profile.
Primary question: can a shopper trust the price and availability presented by a search result?

## Product and boundaries

A merchant submits versioned catalog facts. A shopper discovers relevant offers. The service verifies structured claims against authoritative, timestamped facts and explains verification outcomes. Search relevance, fact freshness and stock reservation are separate concerns.
Initial domain: synthetic consumer electronics; one currency per offer; integer minor currency units (never floating-point money).
A verified result describes a snapshot at verifiedAt, not a guarantee of stock or price at checkout. Reservation is owned by the companion inventory-fulfillment project.

Personas: merchant operator submitting feeds; shopper searching; engineer investigating stale results.
Non-goals: marketplace payments, real scraping, training a foundation model, personalized ad targeting, auction/bidding, purchase guarantees, global deployment.
Success: correct version ingestion, explainable verification, reproducible search-quality evaluation, bounded freshness and recovery from index/publisher failures.

## Functional requirements and invariants

VO-01: Offer identity is (tenantId, merchantId, offerId). Tenant access must not cross identities.
VO-02: Source versions are positive and strictly increasing per identity. Identical same-version payload is an idempotent replay; different content at the same version is a conflict; lower versions are rejected.
VO-03: Persist immutable source versions and a current head. Hash canonical fields for source identity. Receipt time and merchant source time are separate; never make old facts fresh merely by receiving them again.
VO-04: Reject nonpositive versions, negative price/stock, unsupported currency, empty or oversized identifiers, and source timestamps in the future. Initial currency is USD; widen with explicit minor-unit rules.
VO-05: Claims specify priceMinor and currency. VERIFIED requires exact price/currency match, stock > 0, a nondeleted offer and source age strictly below freshnessTTL. At TTL boundary return STALE. Unknown offer, tombstone, mismatch and unavailable outcomes are explicit.
VO-06: LLM outputs are untrusted suggestions. They cannot override authoritative structured facts or grant permissions. Unknown/missing evidence results in abstention.
VO-07: Query results carry source version, source timestamp and index version. Recheck eligibility against authoritative current state before returning verified claims; index consistency is eventual. State can change immediately afterward, so disclose as-of semantics.
VO-08: Deletions publish a higher-version tombstone retained through the replay horizon. Old events cannot resurrect deleted offers.
VO-09: Index updates are monotonic per offer; stale events cannot overwrite newer projections. Rebuild into a separate index and atomically switch an alias after validation.
VO-10: At-least-once delivery cannot duplicate current state changes. Storage commit, outbox and consumer behavior must pass crash-point tests.
VO-11: Evaluation labels must be authored independently of model-generated outputs; report unsupported-claim false acceptance separately from abstention.
VO-12: Availability is informational; no stock mutation or order creation occurs here.

## Architecture

Target flow:
Merchant feed -> ingestion API -> PostgreSQL current/version/outbox tables -> publisher -> Kafka -> index worker -> OpenSearch
Shopper -> search API -> retrieval candidates -> authoritative fact checks -> results with provenance
Optional claim-extraction worker (Python/FastAPI) -> proposed structured claims -> same deterministic verifier
React console -> search, source history, freshness, quarantine and evaluation views

Module boundaries: catalog (versions/facts), verification (pure policy), retrieval (index adapter and ranking), ingestion (feed parsing/limits), delivery (outbox/indexing), API.
Initial P1: catalog and verification, bounded in-memory adapters and REST only. No search engine or LLM is implemented in P1.
P2: PostgreSQL replaces local state. P3: Kafka/OpenSearch deliver actual search. Python/model integration is optional P5 work after a measured non-AI baseline.

## API contract

P1 local-demo endpoints:
- PUT /api/v1/offers — complete versioned source snapshot; returns current offer.
- GET /api/v1/offers/{tenantId}/{merchantId}/{offerId} — exact identity lookup.
- POST /api/v1/verifications — tenantId, merchantId, offerId, priceMinor, currency; returns outcome, sourceVersion (nullable), sourceUpdatedAt (nullable), verifiedAt.
- GET /actuator/health — liveness only; not readiness for planned dependencies.

Offer body: tenantId, merchantId, offerId, version, title, priceMinor, currency, availableQuantity, sourceUpdatedAt, deleted.
Verification precedence: NOT_FOUND -> DELETED -> STALE -> MISMATCH -> UNAVAILABLE -> VERIFIED.
400 validation, 404 unknown lookup, 409 version/payload conflict, 503 bounded demo capacity. ProblemDetail JSON; no stack traces.
Before shared deployment, derive tenant identity from auth, version/document the API change and remove untrusted tenant scope. P2 postgres-local still uses explicitly untrusted demo tenant fields.
P3 GET /api/v1/search?q=&limit=&cursor= returns bounded results and source/index timestamps. Cursor ties to a stable search snapshot; avoid unbounded offsets.
P4 bulk feeds are bounded asynchronous jobs with jobId, checksum, per-row failures and restart behavior; no arbitrary URL fetch/SSRF surface.

## Persistent model (P2 design)

offer_key PK(tenant_id,merchant_id,offer_id): stable identity/lock row for first-write concurrency.
offer_head PK(identity): current version; composite FK references immutable history.
offer_version PK(identity,version): constrained typed price/currency/quantity, source_updated_at, received_at, deleted, complete JSON snapshot and canonical payload hash.
outbox: event_id PK, event_type, tenant_id, aggregate_id/version, schema_version, complete envelope, created_at, published_at. Publisher attempts/leases will be introduced with P3.
Atomic ingestion locks/conditionally updates current head after handling concurrent first inserts; head/version/outbox written together. Conflicting source versions return 409, not silent last-writer-wins.
Index is disposable; PostgreSQL is authoritative. Rebuild must not reintroduce deleted/old data.

## Quality and workload targets

Provisional P3 baseline: 100k synthetic offers across 100 merchants, 100 offered search requests/s plus 20 updates/s for 15 minutes on specified hardware. Aim for search p95 <250 ms, index freshness p95 <5 s and HTTP errors <1%; measure rather than promise.
P6 stretch: 1m offers, skewed hot merchants, multiple API/worker instances and index-node failure. Choose load from actual hardware/cost budget.
Verification correctness: zero false VERIFIED outcomes in independently labeled deterministic fixtures. Retrieval: report nDCG@10/Recall@10 and lexical baseline before embeddings/reranking.
Test future timestamps, TTL equality, stale-but-matching prices, price/currency mismatch, missing records, zero stock, tombstones, duplicate/conflicting/out-of-order versions, tenant boundaries, concurrent updates and replay after a newer delete.
Failure demo: pause the indexer, change price/stock, show authoritative verification prevents stale claims; restart/replay and measure catch-up. This is a future P3 demo, not P1 capability.

## Delivery principles

- A production-oriented reference implementation, not a claim of production readiness or Amazon affiliation.
- Build a modular service before introducing independently deployed components. Separate ownership by module and explicit contracts.
- Ship actual vertical slices, tests, failure experiments and decision records. Commit at genuine milestones with actual timestamps.
- Synthetic merchants, users, inventory and payments only. No employer code, application data, scraped private records or credentials.
- The owner should review the model, explain its tradeoffs, run the demo and make design decisions. AI-assisted scaffolding is documented in CONTRIBUTING.
- No real payments, production customer traffic or billable cloud resources without a separate deployment decision.
- Seven working sessions are an initial sprint estimate, not a promise that the full system is complete in a week. Do not reduce acceptance gates to meet a date.

## Technology decisions

| Component | Decision | Reason / adoption gate |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Maven 3.9.16 wrapper | Existing Java experience, explicit domain types, mature HTTP/testing tooling; versions verified against official documentation and Maven Central |
| Persistence (P2) | PostgreSQL 17, Flyway, Spring JDBC | Explicit transactions, constraints and concurrency behavior; avoid hiding critical SQL behind ORM behavior |
| Messaging (P3) | Apache Kafka, official Java client through Spring Kafka | Ordered per-aggregate events and replay; transactional outbox bridges database commits to at-least-once publication |
| UI (P4+) | React, TypeScript, Vite | Small inspectable product console; not another business logic authority |
| Tests | JUnit, Spring HTTP integration tests; Testcontainers/PostgreSQL and Kafka at their adoption phases | Test business invariants and real storage behavior; never use H2 as proof of PostgreSQL locking |
| Operations (P5+) | Micrometer/OpenTelemetry, Prometheus/Grafana, structured logs, k6 | Trace actual bottlenecks and reproduce measured results |
| Packaging | Nonroot container, Compose for local dependencies; Terraform/AWS later | Reproducible runtime before cloud complexity |
| AWS option | ECS Fargate, RDS PostgreSQL, MSK, S3, ALB, Secrets Manager | Architectural candidate only; estimate costs and obtain approval before provisioning |

PostgreSQL/Kafka/container patch versions and image digests must be pinned and scanned when introduced. These planned components are not installed by the initial domain milestone. No Redis, Kubernetes, service mesh or custom consensus unless a measured need warrants them.

## Security and public-demo gate

The initial profile is local-only, unauthenticated and volatile. Bind to loopback; startup requires an explicit demo profile. It must not be deployed publicly. Do not load actual user data.

Before a shared environment: OIDC JWT verification (issuer/audience/expiry), tenant identity derived from verified claims, server-side authorization on every object, input limits, request quotas, TLS, secret management, least-privilege roles, audit events, dependency/container scans and tenant-isolation tests. Arbitrary tenant IDs in demo bodies are not authentication.

CORS is closed by default. Public responses exclude secrets and stack traces. Structured logs avoid tokens and personal data. Synthetic fixture generators have fixed seeds. Deletion/retention applies to artifacts, logs and backups as well as tables. Define restore verification and rotation procedures before deployment.

## Event contract and delivery

Envelope: eventId (UUID), eventType, schemaVersion, tenantId, aggregateId, aggregateVersion, occurredAt (UTC), correlationId and typed payload. No secrets or personal data in events.
Commit domain state and the outbox record in the same database transaction. Publish keyed by tenant + aggregate. Mark publication only after acknowledgement; a crash may duplicate delivery.
Consumers commit their business side effect and eventId deduplication in one database transaction. Never claim universal exactly-once delivery across PostgreSQL, Kafka and external systems.
Version payloads additively; breaking changes require a new schema/topic migration. Poison events go to bounded quarantine with reason, attempts and an audited replay procedure. Include retention/deduplication-horizon behavior in tests.
P3 ADR must select polling versus CDC, retry caps, partition count, retention and event payload compatibility before implementation.

## Scale, performance and reliability evidence

Capacity stages are proposed workloads, not achieved claims:
1. Laptop correctness: bounded fixtures and deterministic concurrency/failure tests.
2. Single-node integration: realistic database/index/message adapters; baseline throughput and latency.
3. Controlled load: ramp, burst, sustained load and recovery on documented hardware.
4. Optional multi-instance deployment: verify cross-process correctness, load distribution and resource saturation.

Record commit, configuration, CPU/RAM, JVM settings, data cardinality/skew, payload sizes, warmup, run duration, concurrency, offered versus successful throughput, p50/p95/p99, errors/timeouts and cost. Include hot keys/tenants, not just uniform traffic.
No uptime claim can be inferred from a short test. An SLO is a target, and a measured result must link to raw artifacts.
Backpressure: bounded queues and batches, admission limits, connection pool budgets, timeouts and bounded retries with jitter. Inspect deadlocks, lock waits, index lag and retry storms.
Require backup + restore drills and process-kill recovery before calling a release deployable. Single-region writer first; multi-region routing/replication is a documented extension, not an invented implementation.

## Continuity and engineering workflow

Read AGENTS.md, docs/STATUS.md, this spec, docs/ROADMAP.md and relevant ADRs before work. Update STATUS with completed scope, test commands/results, open risks and the precise next task at each milestone. Update the spec before behavior changes; add ADRs for meaningful tradeoffs.
CI runs verification on pushes/PRs with read-only token permissions. Future integration jobs must require dependencies and fail rather than silently skip.
Every phase ends with acceptance evidence, a truthful README and a real commit. Do not pad commits, backdate them or report a future benchmark as completed.
No automatic scheduling of future work is configured by this repository.

## References

- [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html)
- [Kafka delivery semantics](https://kafka.apache.org/40/design/design/)
