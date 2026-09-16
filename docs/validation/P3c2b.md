# P3c2b validation: coordinated index handoff

Date: 2026-09-16. `./mvnw --batch-mode --no-transfer-progress verify` passed
**100 tests**: 43 unit/HTTP/helper and 57 PostgreSQL/Kafka/OpenSearch/process
integration tests, with zero failures, errors or skips. The four-assertion
`node scripts/demo.mjs` walkthrough passed separately. Java 21, local Apple
Silicon, existing digest-pinned PostgreSQL 17.11/Kafka 4.2.1/OpenSearch 3.8.0;
no new dependencies or images. Full gate took 2:13 locally; this is a test-suite
runtime, not a rebuild latency or performance measurement.

## New acceptance evidence

`OnlineRebuildIT` runs ten tests against all three real dependencies:

1. Start boundary precedes snapshot; replay out-of-order updates, a duplicate,
   a deletion and a new identity. The validated candidate contains exactly the
   expected versions/tombstones, excluding an update beyond the sealed end.
   Promote atomically, then consume that later update through the live alias.
2. An indexing pause makes the real consumer rewind a fetched record without
   projection or offset commit. Pre-switch abort preserves the old alias and
   reopens only the owning route.
3. Apply the real alias change while PostgreSQL remains SWITCHING, simulating
   the lost-reply/database-ack boundary. A newly constructed coordinator sees
   the promoted target and finishes forward. Unsafe rollback is refused.
4. A malformed event fails replay without advancing the cursor; abort restores
   the original route.
5. Delete a real Kafka range after capture; sealing fails WINDOW_INVALID and
   remains paused until explicit abort.
6. A latch holds a real consumer after its pre-pause gate check. Seal the replay
   boundary, publish a later update and complete promotion, then release the
   delayed projection/acknowledgement. The consumer still handles the later
   update; external versions prevent regression.
7. A valid-looking forged price at the same source version fails against the
   immutable PostgreSQL record. The entire replay batch, including its earlier
   valid event, rolls back together with its cursor.
8. Expire/reclaim a database coordinator lease; the old owner cannot pause or
   release the replacement's lease. Expiry is injected only in the test fixture.
9. Actually increase broker partitions, then delete/recreate the topic. Both
   changes invalidate captured boundaries; neither is silently accepted.
10. Packaged begin/step/status/abort JVM commands resume from durable state,
    catch up an update and reach ACTIVE. Inherited worker flags are deliberately
    true; commands override them, exit themselves and leave outbox publication
    untouched. A subsequent run against the promoted alias can safely abort.

Four new unit tests cover empty/exactly-10,000-offset windows; identity,
partition, retention, truncation and budget rejection; required upgrade
acknowledgement; and rejection of remote, duplicate, override and rollback flags.
The previous 86 tests continue to pass, including shadow corruption/count
validation, real dependency outages, source concurrency and process recovery.

## Scope and limitations

This completes the bounded local **P3c2b handoff** gate, not all project work.
It uses an indexing pause, not zero-downtime cutover. There is no representative
load, multi-node failover, production availability or recovery-time claim.
The alias/ack test recreates the precise persisted crash boundary; it is not an
OS-level kill during an in-flight OpenSearch request. No independent manual
Compose handoff is claimed: packaged-process integration tests exercise the
documented commands against isolated dependencies.

Every live indexer must use the gate-aware build. Old workers, foreign writers,
compacted/transactional topics and concurrent manual alias administration are
unsupported. Database leases fence checkpoints, not arbitrary external admin
requests from suspended processes. Stop an expired worker before replacing it.
Do not manually clear the durable pause or reverse a switched alias.

Retention loss or unexpected external mutation during SWITCHING can require a
reviewed repair; no emergency destructive override is exposed. Snapshot jobs,
old indexes and expected ledgers are retained, not automatically cleaned up.
PIT pagination, index-quarantine replay/retention, feed jobs/UI, auth, image
security remediation and representative measurements remain open.

See [ADR 0006](../adr/0006-coordinated-index-handoff.md) and
[operator runbook](../HANDOFF.md). CI must be checked against the exact pushed
revision; a local pass does not imply a remote CI result.
