# Backup and restore rehearsal

The automated `BackupRestoreIT` creates a logical PostgreSQL backup and restores
it into a **separate fresh PostgreSQL container**, not the original database.
It checks source-version hashes, the current deletion tombstone, feed row receipts,
idempotent re-upload, immutable triggers, Flyway checksums and replay of a saved
outbox lease after expiry. No user database is overwritten.

```sh
./mvnw -Dtest=ReadinessTest -Dit.test=BackupRestoreIT verify
```

Docker is mandatory. The test owns both containers and cleans them up. Its
generated result is `target/validation/restore.json`; the recorded observation
is [here](validation/p6-restore.json). This tiny synthetic drill proves logical
restorability of these relationships, not production RPO/RTO, point-in-time
recovery, backup encryption, cross-region survival or a database power-loss drill.

## Operator procedure for a dedicated deployment

1. Record application commit, migration version, PostgreSQL image digest, UTC time
   and workload scope. Stop administrative schema changes while preparing a dump.
   Use a separate backup-capable credential and protected credential delivery;
   do not include passwords in shell history, command arguments or public logs.
2. Take a transactionally consistent `pg_dump` archive. Hash it, store securely,
   encrypt off-host, restrict access and define retention appropriate to the data.
   Logical source backups and raw feed content can contain sensitive facts.
3. Provision a NEW isolated target with the matching PostgreSQL major version.
   Restore with errors fatal (`pg_restore --exit-on-error` for custom archives,
   or `psql -v ON_ERROR_STOP=1` for plain dumps). Do not use `--clean` against an
   unresolved/live destination. Ownership/ACLs need separate reviewed provisioning.
4. Validate migration checksums, counts, canonical source hashes, foreign keys,
   head/version consistency, tombstones, feed receipts and idempotency identities.
   Apply the reviewed least-privilege role script; do not launch with owner creds.
5. Keep publishers/indexers/feed workers OFF until replay boundaries are reviewed.
   Restored leases may be unexpired; wait for normal expiry. Never invent new event
   IDs or erase receipts to force work through. Existing idempotent delivery handles
   re-publication but is not exactly-once transport.
6. Rebuild OpenSearch from authoritative PostgreSQL, including tombstones, using
   the documented snapshot/handoff flow. A logical DB restore does not restore
   Kafka offsets or search aliases atomically. If Kafka history predates/exceeds
   the restored source horizon, stop and reconcile; do not blindly resume consumers.
7. Test isolated API evidence and recovery, document measured recovery duration
   and data-loss horizon, then request explicit approval before any traffic switch.

The repository does not automate steps that replace an existing environment.
Production restore approval, secret rotation, encrypted storage, scheduled backup
monitoring and representative-volume timing remain deployment gates.
