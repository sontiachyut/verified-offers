# ADR 0006: bounded Kafka catch-up and coordinated alias handoff

Date: 2026-09-16. P3c2b, implemented through acceptance tests.

This is a local, operator-driven rebuild with an **indexing pause**, not a
zero-downtime or high-availability claim. Ingestion/publishing continue. Search
continues to verify PostgreSQL facts and may omit changed offers while indexing
is paused. All live indexers must run the new gate-aware binary before use;
mixed old/new indexer versions are unsupported. No cloud deployment is implied.

1. Resolve the live alias to exactly one explicit, unfiltered write index.
   Capture Kafka topic UUID, partition set and end offsets BEFORE capturing a
   durable PostgreSQL snapshot. Persist both together with an expected-version
   ledger copied from the immutable snapshot. Existing snapshot-only jobs cannot
   acquire this boundary retroactively.
2. Persist a pause owner in PostgreSQL for the live alias, then capture and seal
   Kafka end offsets. Each live handler checks the gate AFTER poll and BEFORE
   any projection or quarantine/offset acknowledgement. A blocked record is
   rewound. Any handler which passed the gate before the pause necessarily
   already fetched its record; that record is inside the later sealed window.
   Delayed acknowledgements of such records are safe because replay includes
   them. Do not move a consumer group's offsets during rebuild.
3. Replay only the fixed [start,end) window, at most 100 records per step and
   10,000 offsets overall. Preserve topic UUID/partition set, require retained
   offsets and contiguous records, and disable automatic offset reset/commits.
   Compacted/transactional/foreign writers are not supported. Malformed events,
   retention gaps, recreated topics and changed partitions fail closed. Compare
   every event's snapshot with immutable PostgreSQL history before updating the
   expected ledger. Ledger updates and replay cursors commit atomically. Keep
   only the greatest source version, including tombstones.
4. Materialize that ledger as a NEW immutable candidate snapshot and run the
   existing bounded shadow builder/validator. The original snapshot is retained;
   no mutable rebuild overwrites an already validated snapshot. Candidate rows
   stay capped at 100,000; both snapshots count toward the ten-job local budget.
5. Only after full validation, persist SWITCHING before external alias work.
   Confirm pause ownership, unblock the candidate, atomically remove the old
   live alias (`must_exist=true`) and add it to the candidate. Never delete the
   old index. Confirm the alias target before marking ACTIVE and releasing the
   pause in the same database transaction. Normal consumers resume at their
   existing offsets; duplicate records cannot regress versions.

Each coordinator step has a 60-second token-fenced database lease. Database
transactions contain database work only; network calls use bounded clients.
All errors leave a durable inspectable state; a crash never automatically
releases the indexing pause. Fixed error codes, not event bodies, are reported.

SWITCHING is a reconciliation state: if an alias request succeeded but its reply
or database acknowledgement was lost, the next step observes the new target and
finishes the same handoff. It must not blindly send a reverse alias change.
Abort is allowed only BEFORE SWITCHING, preserves the old live alias, releases
only this run's pause, and retains evidence. Abort/rollback after switching is
refused: switching back could discard newer indexed updates. Forward recovery
or a fresh coordinated rebuild is required. Operators must not manually edit
aliases, unpause a run or resume suspended expired workers during handoff.

The private candidate remains identifiable by its job UUID and private alias.
Promotion does not transfer it to another run. Retention, auth, representative
load tests, PIT pagination and stronger multi-instance administration remain
separate gates. Local administrators can bypass all these controls; the owner
marker is not cryptographic authorization.

References:
- [Kafka manual assignment/seek and offset handling](https://kafka.apache.org/42/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
- [OpenSearch atomic alias actions](https://docs.opensearch.org/latest/api-reference/alias/aliases-api/)
