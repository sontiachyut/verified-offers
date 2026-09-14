# Outbox recovery — P3a

The catalog commits an offer, version history and a complete event envelope in
one PostgreSQL transaction. `PostgresOutbox` and `OutboxRelay` now provide the
next boundary: claiming, delivering, acknowledging and recovering those events.
An actual Kafka adapter and background scheduling are the next milestone.
Starting `postgres-local` still does **not** publish events.

## Run the recovery scenarios

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Requires Java 21 and Docker. `OutboxRelayIT` starts an isolated PostgreSQL
container and supplies controlled acknowledging/failing sinks. The test
`crashAfterDestinationAcceptsReplaysSameEventAndPayload` records a destination
acceptance, interrupts the relay before marking success, expires the lease, and
creates a new relay. It observes two deliveries with the same ID, key and
payload, one committed source version, and a final publication timestamp.
The crash is injected with an uncaught test `Error`; it does not kill a broker
or establish Kafka delivery. The existing P2 process test does kill/restart the
packaged API to validate catalog recovery.

## Worker contract

`publishNext()` claims at most one event and returns:

| Result | Meaning |
|---|---|
| `IDLE` | No eligible event claimed in this poll; some rows may be locked, waiting or quarantined |
| `PUBLISHED` | The sink acknowledged and the current lease marked publication |
| `RETRY_OR_QUARANTINED` | A send failed and its retry/quarantine state was saved |
| `LEASE_LOST` | A send completed/failed after ownership expired or changed; no acknowledgement recorded |

Database failures propagate to the caller; an interrupted send restores the
thread interrupt flag. A future scheduler must pause on idle/failure, stop on
interruption, and avoid busy loops. The sink must wait for actual destination
acknowledgement with bounded I/O below the 30-second lease. P3a deliberately has
no default sink that could mark an unsent event as published.

All database transitions use short independent transactions. A relay cannot run
inside an ambient database transaction. Separate workers can claim different
rows without waiting on another worker's network operation. The retry budget is
eight claims, including abandoned claims. Lease expiry uses PostgreSQL time;
tests move eligibility timestamps directly to avoid wall-clock sleeps.

## Inspect a local development database

Use your existing local database connection from [POSTGRES.md](POSTGRES.md).
These queries are read-only and avoid dumping full event payloads:

```sql
SELECT
  count(*) FILTER (WHERE published_at IS NOT NULL) AS published,
  count(*) FILTER (WHERE quarantined_at IS NOT NULL) AS quarantined,
  count(*) FILTER (WHERE published_at IS NULL AND quarantined_at IS NULL
    AND lease_until > statement_timestamp()) AS leased,
  count(*) FILTER (WHERE published_at IS NULL AND quarantined_at IS NULL
    AND (lease_until IS NULL OR lease_until <= statement_timestamp())) AS waiting
FROM outbox;

SELECT event_id, attempts, last_error, next_attempt_at, lease_until, quarantined_at
FROM outbox WHERE published_at IS NULL
ORDER BY created_at, event_id LIMIT 50;

SELECT event_id, requested_at, previous_attempts, reason
FROM outbox_replay ORDER BY requested_at DESC LIMIT 50;
```

After investigating and repairing the destination, local administrative code
can call `requeueQuarantined(eventId, reason)` on `PostgresOutbox`. It returns
false unless the event is currently quarantined and unpublished. Replay resets
attempts and eligibility and commits an audit row atomically. Concurrent replay
requests cannot create duplicate audit records for the same transition.
Reasons should describe the repair and must not contain secrets. There is no
HTTP replay endpoint or automatic replay of quarantined events in this slice.

The event payload, ID and source version never change on replay. Full snapshots
may arrive out of order; upcoming consumers must enforce monotonic versions and
retain tombstones. See [ADR 0003](adr/0003-outbox-delivery.md) for the broker,
projection and rebuild contracts and [P3a evidence](validation/P3a.md) for limits.
