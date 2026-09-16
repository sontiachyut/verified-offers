# Local search walkthrough

Synthetic data only. API, PostgreSQL, Kafka and OpenSearch are unauthenticated
local development services, not a production deployment. Keep loopback bindings.
OpenSearch adds a 512 MiB JVM heap plus native overhead. Stop the stack when done.
Requirements: Java 21, Docker (at least 4 GiB available), Compose, curl, jq.

## Start dependencies and provision once

Follow [KAFKA.md](KAFKA.md) to set database credentials, start PostgreSQL/Kafka and
create the three-partition `offers.v1` topic. Then, from the repository root:

```sh
docker compose --profile search up -d --wait opensearch
jq '. + {aliases: {"offers-local": {is_write_index: true}}}' src/main/resources/search/mapping.json |
  curl --fail-with-body -sS -X PUT http://127.0.0.1:9201/offers-local-v1 \
    -H 'Content-Type: application/json' --data-binary @-
```

The index-creation command intentionally fails if the index already exists; do
not delete an existing index to make it pass. Reuse the existing alias and group
on normal restarts. An index and its consumer group form a pair: do not reuse a
caught-up group's offsets with a new empty index. Kafka retains seven days, so
starting a fresh group is **not** a complete database rebuild. Use the
[coordinated handoff](HANDOFF.md) for bounded Kafka catch-up and a recoverable
alias switch with an indexing pause. The separate [snapshot command](REBUILD.md)
only builds a validated read-only shadow and cannot promote it.

Start the packaged API with the database environment from KAFKA.md still set:

```sh
./mvnw package
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=postgres-local \
  --offers.publisher.enabled=true \
  --offers.publisher.bootstrap-servers=127.0.0.1:9094 \
  --offers.search.enabled=true \
  --offers.search.endpoint=http://127.0.0.1:9201 \
  --offers.search.index=offers-local \
  --offers.indexer.enabled=true \
  --offers.indexer.bootstrap-servers=127.0.0.1:9094 \
  --offers.indexer.group=offers-local-v1
```

`package` is a local launch convenience, not the full `./mvnw verify` acceptance
gate. Search and indexer default off; indexer requires search configuration.

## Submit and search

Use an unused synthetic offer ID or increment the version for an existing ID:

```sh
jq -n --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{tenantId:"demo",merchantId:"synthetic",offerId:"mint-keyboard",version:1,
    title:"Mechanical keyboard",priceMinor:9900,currency:"USD",availableQuantity:8,
    sourceUpdatedAt:$now,deleted:false}' |
  curl --fail-with-body -sS -X PUT http://127.0.0.1:8081/api/v1/offers \
    -H 'Content-Type: application/json' --data-binary @-
curl --fail-with-body -sS 'http://127.0.0.1:8081/api/v1/search?tenantId=demo&q=keyboard&limit=10' | jq
```

Indexing is asynchronous; retry search after a few seconds. Each returned result
contains the authoritative offer, index version/source timestamp and VERIFIED
as-of evidence. After five minutes without a fresh merchant source update, it
disappears. This is not a reservation or a promise of stock at checkout.

The endpoint returns only exact, eligible snapshots. Up to 5 × limit title matches
per page are rechecked against PostgreSQL; lag, deletion, stock loss, price change
or TTL expiry can produce fewer results. There is no exact total. Follow
`nextCursor` until null, even when a page's results are empty. See the
[pagination guide](PAGINATION.md) for continuation, expiry, cancellation and
resource admission. PIT ordering is stable across refresh/alias handoff, but
current PostgreSQL eligibility is checked on every page.
`q` is 1–200 characters; `limit` is 1–50. Tenant is
untrusted demo scope, not authorization. A dependency outage returns 503 rather
than trusting stale index data. Health is liveness, not indexing-lag readiness.

## Observe stale-index protection

Stop the API with Ctrl-C. Restart the same command **without the three indexer
arguments**, leaving search and publishing enabled. Submit the same offer at
version 2 with priceMinor 10900 and a fresh sourceUpdatedAt. Search should omit
the old indexed offer: PostgreSQL already knows that its price changed. Restart
with indexer enabled and the same group; after catch-up, search shows version 2.
Repeat with a higher-version deletion; old events cannot resurrect it.

The automated `OpenSearchIT` additionally exercises actual OpenSearch pause/503,
Kafka replay after projection/offset-commit failure, poison-event quarantine,
older/duplicate/tombstone delivery, and database freshness checks. These are
correctness experiments, not measured scale or failover results.

## Quarantine and recovery limits

`index_quarantine` records topic, partition, offset, fixed reason, payload SHA-256
and timestamp. No raw event bodies or exception strings are stored. Offsets move
only after this insert succeeds; duplicates use the same row. Investigate the
original Kafka record within retention. Do not delete records or reset offsets
casually. Audited quarantine replay/retention tooling remains an explicit next
operator task; the publisher's outbox replay is a different mechanism.

Worker counters: `offers.indexer.records` with `projected`, `quarantined`, `retry`.
Replay no-ops count as projected/acknowledged records, not new documents. Counters
are registered but no public metrics endpoint is enabled.

## Stop without deleting data

Stop the API with Ctrl-C first, then:

```sh
docker compose --profile search --profile messaging stop opensearch kafka postgres
```

Named volumes remain. Quit Docker Desktop if no other work needs it. Do not run
global prune or delete shared volumes. See [ADR 0004](adr/0004-search-projection.md)
for the original projection design and [ADR 0007](adr/0007-stable-search-pages.md)
for current pagination behavior.
