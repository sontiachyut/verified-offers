# P3b validation — actual Kafka delivery

Recorded: 2026-09-14. Synthetic local fixtures only.

## Reproduce and observed result

```sh
./mvnw --batch-mode --no-transfer-progress verify
node scripts/demo.mjs
```

macOS arm64, Java 21.0.8, Spring Boot 4.1.1, official Kafka Java client 4.2.1,
Testcontainers 2.0.5, PostgreSQL 17.11 and Apache Kafka 4.2.1 (Java broker).
Kafka image digest:
`sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0`.

**55 tests pass: 29 unit/HTTP/helper tests and 26 real dependency/process
integration tests; zero failures, errors or skips.** The existing HTTP demo
passes. Reports are generated in `target/surefire-reports` and
`target/failsafe-reports`. CI runs the same full Maven gate and HTTP demo.

## New evidence

Five `KafkaDeliveryIT` tests start a real single-node broker and PostgreSQL:

1. Both offer updates and tombstones are consumed from Kafka with unchanged
   persisted envelopes and tenant/aggregate keys. Same-key events use the same
   partition; the database marks publication after acknowledged sends.
2. An injected crash after a real broker acknowledgement leaves publication
   unmarked. A new producer replays the same ID/payload with a later Kafka offset
   after lease expiry. This demonstrates why producer idempotence is insufficient
   to deduplicate outbox replay across worker lifetimes.
3. The broker is paused after a successful warm-up send. The next send times out,
   leaves the event unpublished, and saves retry state. Unpausing the same broker
   allows retry and consumption of the event.
4. The packaged API, started with publishing enabled, commits an offer and its
   background worker sends the record to Kafka. Process shutdown is graceful.
5. With the broker paused, the packaged API still ingests and reads an offer.
   Shutdown during a live publication attempt completes without forced process
   termination; the unpublished event subsequently recovers after broker resume.

Four new unit tests confirm that a locally queued future is not an
acknowledgement, asynchronous send failures propagate, a database error does not
cancel subsequent polls, and an interrupted worker does not claim more work.

The process helper now fails if graceful shutdown exceeds 35 seconds rather than
silently treating a forced kill as normal shutdown. Kafka containers bind the
exposed client port to loopback and are always cleaned up by the test lifecycle.

## Limits

The crash between broker acknowledgement and database marking is injected with
an uncaught `Error`; the Kafka record and PostgreSQL state are real. Broker pause
is not a multi-node failover or broker-disk-loss test. Replication factor is one.
Retries can duplicate records and change source-version arrival order.

No index consumer, search API, throughput/lag benchmark, broker authentication,
production readiness or high-availability claim is established by these tests.
The Kafka image scan has unresolved findings; see
[the security record](KAFKA-IMAGE-SECURITY.md). Default profiles leave publishing
disabled unless explicitly enabled under postgres-local.
