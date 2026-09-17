# Index quarantine recovery

First stop the faulty producer and inspect bounded metadata, never dump raw
customer data into logs. This local workflow uses synthetic fixtures and OS/DB
operator permissions, not an unauthenticated HTTP administration endpoint.

```sql
SELECT q.topic,q.partition_id,q.record_offset,q.reason,q.payload_sha256,
       q.quarantined_at,r.reconciliation_id,r.attempts,r.completed_at
FROM index_quarantine q LEFT JOIN index_reconciliation r
  USING (topic,partition_id,record_offset)
ORDER BY q.quarantined_at,q.topic,q.partition_id,q.record_offset LIMIT 50;
```

An invalid envelope cannot be replayed as a valid offer. After reviewing producer
evidence and establishing which authoritative offer should be restored, save an
explicit association (example identifiers only):

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --reconcile=prepare --topic=offers.v1 --partition=0 --offset=42 \
  --tenant=demo --merchant=synthetic --offer=keyboard \
  --operator=local-owner --reason=producer-repaired
```

Use the returned UUID for `--reconcile=status --id=UUID` and then
`--reconcile=step --id=UUID`. Database environment variables are the same as the
PostgreSQL runbook. Step also requires `OFFERS_SEARCH_ENDPOINT` and
`OFFERS_SEARCH_INDEX`; existing adapter validation requires loopback. All commands
force web and background workers off. Step obeys the live-indexing pause gate.

Preparation snapshots the current immutable source version. An identical prepare
retry returns that same intent even if source changes. A changed association,
operator or reason returns a conflict. No raw poison payload is accepted. This
is operator-supplied source reconciliation, not proof the original event matched
that source and not arbitrary schema-repair replay. Leave unknown associations
unresolved; use a reviewed source rebuild where appropriate.

Projection and database completion cannot be atomic. If the process exits between
them, inspect status and repeat the SAME step. External index versions make replay
safe, including deletion tombstones. At most eight attempts; exhaustion needs
operator review. Do not reset counters, delete evidence, or modify source history
to bypass this limit. A current-source verification still excludes stale or
superseded projections. Reconciliation does not refresh source timestamps.

## Retention contract

Kafka retains seven days in the local stack. PostgreSQL source versions, outbox
events, feed receipts, tombstones, quarantine and reconciliation evidence are
retained indefinitely in this bounded reference deployment. Immutable triggers
protect history/evidence from routine update/delete; they do not constrain a DB
administrator. There is deliberately no age-based deletion that could resurrect
old versions or reuse idempotency keys. Export/backup, verify a restore and review
replay/idempotency horizons before designing any destructive lifecycle migration.

Feed capacity (100 jobs), snapshot capacity (10 jobs) and eight reconciliation
attempts remain explicit admission limits. Hitting them is a visible operational
limit, not an excuse to disable constraints. This project does not claim an
unbounded long-running production retention service. Retention automation is an
open release gap until a reviewed archival design preserves these guarantees.
