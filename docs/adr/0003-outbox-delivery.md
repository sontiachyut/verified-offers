# ADR 0003: recoverable outbox delivery and monotonic projections

Date: 2026-09-14. Status: accepted; implemented incrementally in P3.

## First acceptance slice: P3a

Build the PostgreSQL lease store and a transport-independent relay first. Test
the publish/acknowledgement crash boundary with real PostgreSQL and a controlled
delivery sink. A sink returning normally means the destination acknowledged the
event; merely enqueueing a future is insufficient. This slice does not connect
Kafka, start a background publisher, or implement search.

Use polling instead of CDC for the initial local system: it keeps the committed
outbox, recovery state and operator actions inspectable in one database. Claim
one event at a time in a short transaction using `FOR UPDATE SKIP LOCKED`.
Concurrent workers skip locked rows. Never hold a transaction across network I/O.
The relay rejects invocation inside an ambient transaction.

Every claim has a new random UUID token, a 30-second lease and a persisted attempt
number. PostgreSQL statement time controls eligibility and expiry; worker clocks
do not decide ownership. Success and failure updates require the current token
and an unexpired lease. An expired worker cannot acknowledge a newer worker's
claim. A crash after destination acknowledgement but before database marking
replays the exact persisted event ID and payload after expiry. This is
**at-least-once delivery**, not exactly-once execution. Fencing cannot cancel a
network request already in flight, so consumers must tolerate duplicates.

On a send failure, release the lease and retry with exponential backoff and
jitter (1-second base, 60-second cap). Limit attempts to eight, including claims
abandoned by process crashes. Quarantine exhausted events durably; each claim
call sweeps at most 32 expired exhausted rows. Store only fixed error codes,
never exception text or credentials. Operator replay resets the attempt budget
and appends an audit row with the event ID, previous attempts, time and reason.
Do not delete events or regenerate IDs on replay. Replay is an explicit local
administrative operation, not an unauthenticated HTTP endpoint.

## Kafka and retrieval contracts for subsequent P3 slices

Preserve schema-v1 envelopes already written by P2. The record key is
`tenantId:merchantId:offerId`; identifiers exclude colons. Full snapshots and
higher-version deletion snapshots use the same key and event ID on retry.

The initial Kafka topic will use three partitions, seven-day retention and a
single local broker (no high-availability claim). Configure an idempotent
producer, `acks=all`, bounded buffers and a delivery timeout below the lease.
Pin and scan the broker image when the adapter is introduced. Kafka integration
tests must confirm acknowledgement, outage/recovery and replay before enabling
the publisher in a local application profile.

Multiple relays and delayed retries can publish source versions out of order,
even with a stable partition key. Each search document must therefore retain
the greatest aggregate version, including tombstones; physically deleting the
document would allow old events to resurrect it. Quarantined events do not
block unrelated or newer full snapshots. Unknown envelope schemas go to
quarantine. Broker offset acknowledgement must follow durable projection;
replay of an equal/older version is a no-op. Consumer deduplication must not
depend solely on a short-lived in-memory event-ID cache.

Rebuild into a new OpenSearch index from authoritative PostgreSQL heads,
including tombstones, while retaining/replaying a bounded catch-up event range.
Validate versions and completeness before switching an alias. Search rechecks
current PostgreSQL facts before returning claims. OpenSearch ordering, rebuild,
catch-up and stale-candidate tests remain a separate P3 acceptance gate.

## Evidence and references

### P3b implementation decisions

Use the official Kafka Java client directly, with Spring owning the sink and
scheduled worker lifecycle. This replaces the preliminary Spring Kafka wrapper
choice: the adapter needs one explicit synchronous acknowledgement boundary and
no listener containers yet. Dependency versions come from the existing Spring
Boot BOM (Kafka client 4.2.1). Use the matching Apache Kafka 4.2.1 broker image,
pinned by manifest digest in tests and Compose, and record its vulnerability scan.

One fixed-delay worker publishes one event per 250ms poll. Enable it only with
`offers.publisher.enabled=true` under `postgres-local` and an explicit bootstrap
address. Require a pre-created `offers.v1` topic (three partitions, seven days of
retention); disable broker auto-creation. No admin topic creation at API startup.
Kafka uses 5s max-block, 10s delivery, 5s request and 0ms linger limits, with a
12s Future wait: nominal send/ack work fits within the 30s database lease. Producer
idempotence and `acks=all` do not deduplicate outbox retries after a worker crash.
Bound producer memory to 4MiB and record size to 64KiB. Close producers with a 5s
deadline; stop the scheduler before its dependencies and allow an in-flight poll
up to 25s on shutdown. A lost lease remains replayable after expiry.

The worker records per-result counters and catches database exceptions so one
failed poll cannot permanently cancel scheduling. No payloads or exception text
are logged by the poller. A broker failure is a retry/quarantine outcome; the
catalog API can remain available while the broker is down. `/actuator/health`
does not claim broker readiness or bounded indexing lag.

Tests will use actual broker acknowledgement, consume original envelopes,
pause/unpause the isolated broker for timeout/recovery, replay after an injected
acknowledgement/database gap, and start the packaged API with publishing enabled.
The outage test is a single-node pause, not a distributed broker failover test.

P3a tests must cover crash after publish, competing workers, expiry fencing,
failed/interrupted sends, retry timing, exhausted crash recovery, audit replay,
tenant keys and unchanged tombstone envelopes. They establish database/relay
behavior, not broker delivery, throughput or index correctness.

- [PostgreSQL queue locking](https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [Kafka delivery semantics](https://kafka.apache.org/40/design/design/)
