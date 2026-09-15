# Kafka delivery — P3b

The optional publisher sends committed outbox envelopes to Apache Kafka 4.2.1.
PostgreSQL remains the source of truth. A record is marked published only after
Kafka acknowledges it; the consumer/search implementation is the next phase.

## Start the local flow

Use the local database environment setup in [POSTGRES.md](POSTGRES.md) first.
Both dependencies run on this Mac; the API and exposed dependency ports bind to
loopback. Use only synthetic data. Java 21, Docker and Docker Compose are required.

```sh
docker compose --profile messaging up -d
docker compose --profile messaging ps
```

Wait for PostgreSQL and Kafka to be healthy, then create the topic explicitly:

```sh
docker compose --profile messaging exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --create --if-not-exists \
  --topic offers.v1 --partitions 3 --replication-factor 1 --config retention.ms=604800000

docker compose --profile messaging exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --describe --topic offers.v1
```

Confirm three partitions and seven-day retention. `--if-not-exists` does not
repair a differently configured existing topic. Broker auto-creation is disabled.

Start the API with publishing explicitly enabled:

```sh
export OFFERS_PUBLISHER_ENABLED=true
export OFFERS_PUBLISHER_BOOTSTRAP_SERVERS=127.0.0.1:9094
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres-local
```

In another terminal, ingest a synthetic offer (the timestamp will correctly
produce stale verification after its freshness window; publication still works):

```sh
curl --fail-with-body -X PUT http://127.0.0.1:8081/api/v1/offers \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","merchantId":"electronics","offerId":"headphones","version":1,"title":"Synthetic headphones","priceMinor":4900,"currency":"USD","availableQuantity":5,"sourceUpdatedAt":"2026-01-01T00:00:00Z","deleted":false}'
```

Inspect the real Kafka record:

```sh
docker compose --profile messaging exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic offers.v1 --from-beginning \
  --max-messages 1 --timeout-ms 15000 --formatter-property print.key=true
```

The key is `demo:electronics:headphones`. The JSON value carries the original
event ID, version and complete offer snapshot. With an existing topic the first
record may be an earlier event; inspect its identity rather than assuming it
belongs to the latest request. An identical source replay creates no new event.

Use the read-only queries in [OUTBOX.md](OUTBOX.md) to inspect publication,
retry and quarantine state. A published row means Kafka acknowledged its record,
not that a search projection has consumed it.

## Outage and shutdown behavior

To exercise a local broker interruption while the API is running:

```sh
docker compose --profile messaging stop kafka
```

Ingest a higher offer version. The API can commit it while Kafka is unavailable.
The worker records failed sends and backs off; after eight attempts the event is
quarantined. Restart the same broker/volume to recover:

```sh
docker compose --profile messaging start kafka
```

Pending events retry automatically. Already quarantined events require explicit
audited replay through the local administrative store method described in
OUTBOX.md. There is no public replay endpoint. Restarting the broker does not
reset an exhausted attempt budget.

One worker sends one event per poll with a 250ms fixed delay after completion.
This intentionally limits laptop work; it is not a throughput benchmark. Kafka
send and acknowledgement deadlines fit within the 30s lease. Micrometer records
`offers.publisher.polls` with result tags (`published`, `idle`,
`retry_or_quarantined`, `lease_lost`, `database_error`). Metrics are instrumented
but the HTTP metrics endpoint remains unexposed under default settings.

Ctrl-C the API to let an in-flight send finish within the shutdown deadline, then:

```sh
docker compose --profile messaging stop
unset OFFERS_PUBLISHER_ENABLED OFFERS_PUBLISHER_BOOTSTRAP_SERVERS
```

This preserves the named PostgreSQL and Kafka volumes. Docker Desktop can then
be quit. Without the publisher environment variables, `postgres-local` only
stores outbox events. `local-demo` never creates the Kafka publisher.

## Acceptance evidence and limits

`./mvnw verify` runs real Kafka/PostgreSQL integration tests including broker
pause/recovery, replay after broker acknowledgement and packaged API publishing
and shutdown. It fails if Docker is unavailable. Tests own separate temporary
containers and do not use Compose data. The consumer in tests verifies delivery;
an application index consumer is not implemented yet.

The local broker has one replica, plaintext listeners and no authentication. An
acknowledgement is not proof of resilience to loss of that broker's data volume.
Producer idempotence does not prevent a second copy when the outbox replays an
event after a crash. Consumers must enforce versions and handle duplicate IDs.
See [ADR 0003](adr/0003-outbox-delivery.md) and the
[Kafka image findings](validation/KAFKA-IMAGE-SECURITY.md).
