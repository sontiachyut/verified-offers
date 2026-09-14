# P3a validation — recoverable outbox relay

Recorded: 2026-09-14. Synthetic fixtures only.

## Commands and observed result

```sh
./mvnw --batch-mode --no-transfer-progress verify
node scripts/demo.mjs
```

Local macOS arm64, Java 21.0.8, Spring Boot 4.1.1 and Testcontainers 2.0.5.
The same pinned PostgreSQL 17.11 image used by P2 was used; no new dependencies
or container images were introduced. Existing image security findings remain
documented in [IMAGE-SECURITY.md](IMAGE-SECURITY.md).

**46 tests passed: 25 unit/HTTP/helper and 21 database/process integration tests,
with zero failures, errors or skips.** The HTTP walkthrough passed all four
existing scenarios. Raw Surefire/Failsafe reports are generated under `target/`.

## New database evidence: 13 relay tests

- Crash injected after sink acknowledgement and before marking publication:
  the restarted relay sends the same event ID, key and payload after lease expiry.
- Forty concurrent claims through two independent connection pools have forty
  distinct event IDs; a live lease prevents another claim of the same row.
- Expired and replaced lease tokens cannot acknowledge or change retry state.
- Failed sends release ownership, set a bounded backoff and store only a fixed
  error code. A recovered sink subsequently publishes the event.
- Eight failed attempts quarantine an event. Explicit replay preserves identity,
  restarts the budget and records a single audit entry.
- A crash on the eighth claim is quarantined when its lease expires, while
  another offer remains claimable.
- Tombstone envelopes and tenant-specific partition keys preserve source facts.
- Destination I/O runs outside transactions; another connection can lock the
  outbox row during delivery. Ambient-transaction relay calls are rejected.
- Interrupted delivery preserves the interrupt flag and saves a retry reason.
- An injected database acknowledgement failure preserves the claim for recovery
  after the destination already accepted the event.
- An audit insert failure rolls back an administrative replay.
- A populated V1 schema upgrades to V2 with unchanged IDs, payloads and previous
  publication timestamps; only its unpublished event becomes claimable.
- A destination acknowledging after lease expiry cannot falsely record success.

## Limits and next gate

The destination here is a controlled test sink. The crash boundary is simulated
with an uncaught `Error`, followed by lease expiry and a new adapter; this is not
a broker/process crash experiment. Existing P2 API process-recovery tests also
continue to pass. No throughput, broker availability or exactly-once claim follows
from these results. Retry timing is bounded, but no large backlog/load experiment
has been run.

P3a is complete; the overall P3 phase remains open. P3b must add an acknowledged
Kafka adapter, pinned/scanned broker image and actual broker outage/replay tests.
P3c adds monotonic OpenSearch projections, rebuild and authoritative verification
of search candidates. The local application has no background publisher enabled.
