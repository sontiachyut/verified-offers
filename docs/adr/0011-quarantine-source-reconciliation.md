# ADR 0011: recover from poison records using authoritative evidence

Date: 2026-09-16. Status: accepted for implementation.

Index quarantine retains record position, timestamp and payload digest, not raw
poison data. It cannot safely reconstruct a malformed event, and Kafka retention
may already have expired. Never invent a replacement event or silently drop the
quarantine row. Generic parser replay is not meaningful for a still-invalid schema.

Provide an explicit local operator reconciliation workflow: after investigating
the producer, the operator identifies the authoritative offer to re-project.
Persist an immutable intent tied to the quarantine position, the operator label,
reason code, source identity and exact immutable source version. This records an
operator-supplied association, NOT an automatically proven mapping from poison
payload to offer. Do not use this action if that association is unknown; leave the
row unresolved and repair/rebuild using reviewed source evidence instead.

One intent per quarantine record, idempotent only for identical operator inputs.
Execution reads that saved version from immutable history and projects using the
existing external-version adapter and live-indexing gate. No database transaction
spans index I/O. Complete only after acknowledgement; an acknowledgement/database
crash replays the same source snapshot safely. Pending/complete state and attempt
count are bounded (eight attempts); operator diagnosis, not infinite retries,
handles exhaustion. Retain both original quarantine and intent indefinitely in
this bounded reference system. No destructive retention command or HTTP admin API.

This closes a source-reconciliation recovery path, not arbitrary raw-event replay
or proof that the original malformed event was valid. Current-source verification
still filters stale snapshots; neither repair nor ingestion refreshes source time.
