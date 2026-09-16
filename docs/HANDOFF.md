# Coordinated search-index handoff

Local synthetic data only. This P3c2b workflow catches up and promotes a **new**
snapshot using a durable indexing pause. It is not zero-downtime or HA rebuild.
Ingestion and publishing can continue; search still verifies PostgreSQL facts
and can return fewer offers while projection is paused. Nothing runs on a timer.

## Preconditions

Follow [SEARCH.md](SEARCH.md) for PostgreSQL credentials, Kafka topic provisioning,
the explicit live alias and the running API/indexer. Build with `./mvnw verify`.
Upgrade **every indexer** to this gate-aware build before beginning; starting an
old binary during a handoff bypasses the pause and is unsupported. The new
indexer registers `index_route` on startup. The command refuses an unregistered
route, but cannot discover old binaries: the upgrade acknowledgement is an
operator responsibility, not automatic fleet verification.

Keep the same database, Kafka topic and OpenSearch endpoint. The live alias must
resolve to exactly one explicit write index, without filters or routing. Do not
change partitions, recreate/compact the topic, edit aliases, reset consumer
offsets or run snapshot commands against the coordinator's candidate. Only this
application's nontransactional publisher is supported in the replay window.

Limits: 32 partitions, 10,000 Kafka offsets per handoff, 100 events/offers per
batch, 100,000 expected offers, ten retained snapshot jobs. A successful handoff
uses **two** snapshot jobs; leave room for both. Cleanup is not implemented.
The seven-day topic retention is not a guarantee that an old consumer can resume:
check lag/retention first. Never substitute a new empty index for an existing
consumer group's caught-up index.

## Begin and advance deliberately

Use the database environment from [POSTGRES.md](POSTGRES.md). Default local
ports below match Compose; replace them only with your actual loopback ports.

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --online=begin --endpoint=http://127.0.0.1:9201 --alias=offers-local \
  --bootstrap-servers=127.0.0.1:9094 --ack-upgraded=true
```

Copy `run.id` from the JSON output. `begin` captures topic identity/end offsets
before the database snapshot; it does not yet pause indexing. Promptly advance:

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --online=step --run=<run-uuid> --bootstrap-servers=127.0.0.1:9094
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --online=status --run=<run-uuid>
```

Repeat `step` deliberately while progress is healthy. Every invocation is a
separate process; inspect state/cursors rather than assuming a fixed step count.
Endpoint/alias are persisted and cannot be overridden on a later step. All
commands force a nonweb PostgreSQL process with publisher/indexer/search disabled,
including when environment flags would otherwise enable those workers. They do
not stop the independent live application.

| Persisted state | What the next step does |
|---|---|
| `CAPTURED` | Claim the live alias's durable pause. |
| `PAUSED` | Seal the fixed ending Kafka boundary. |
| `REPLAYING` | Validate/replay a bounded batch into the expected-version ledger; once complete, capture a separate candidate snapshot. |
| `BUILDING` | Project and fully validate the candidate using durable shadow checkpoints. |
| `SWITCHING` | Reconcile the atomic alias handoff; confirm the target, mark ACTIVE and release the pause. |
| `ACTIVE` | Terminal success; live consumers continue at their own existing offsets. |
| `ABORTED` | Terminal pre-switch cancellation; old live alias preserved. |

Output includes `result`, `run`, per-partition `cursors`, and `candidate` progress
when available. No lease token or raw offer/event body is returned. The old index
is retained. A promoted candidate's private alias remains for identification;
do not use it as a separate live route.

## Failures and recovery

- Exit `0`: successful progress/status/terminal operation; check the state.
- Exit `3`: `RETRY`, `WAITING`, or a nonterminal leased run. Inspect before retrying.
- Exit `2`: invalid options, dependency/setup failure, or refused operation.

The pause is durable and does **not** expire with the 60-second coordinator lease.
After a crash, stop the old process, wait for lease expiry, and run the same step.
Do not resume a suspended expired worker or manually clear the pause. Database
checkpoint mutations are token-fenced; this is not distributed authorization for
arbitrary concurrent OpenSearch administration.

`STEP_FAILED` requires checking dependency health, route ownership and candidate
status. `EVENT_INVALID` means the window contains a malformed event or facts that
do not match immutable source history. `WINDOW_INVALID` includes topic identity,
partition, retention, truncation or 10,000-offset budget violations.
`CANDIDATE_INVALID` requires inspecting the candidate's validation result. Failed
batches do not advance the cursor or partially update the expected ledger.

Before `SWITCHING`, cancel safely if the window cannot be repaired:

```sh
java -jar target/verified-offers-0.1.0-SNAPSHOT.jar \
  --online=abort --run=<run-uuid>
```

Abort verifies the old alias target, releases only this run's pause and retains
all evidence. Then address the cause before a fresh run. Do not repeat a poisoned
window indefinitely or edit immutable rows to pass validation.

At `SWITCHING`, an alias request may have succeeded even if the client reported
failure. Retry the same step: it accepts either the expected old target or its
own new target, and completes **forward**. Abort/rollback is refused here and
after `ACTIVE`; reversing the alias could discard newer projections. If topic
retention/identity is lost during this uncertain state, or an administrator
changed the alias unexpectedly, automated recovery remains blocked. Preserve
state for a reviewed repair; there is no destructive emergency override.

Do not stop the API permanently while leaving an unfinished pause unnoticed.
Complete or safely abort the run first, then use the normal
[shutdown instructions](SEARCH.md#stop-without-deleting-data). No volume removal
or global Docker pruning is part of this workflow.

See [ADR 0006](adr/0006-coordinated-index-handoff.md) and
[acceptance evidence](validation/P3c2b.md). Image security, authentication,
retention, stable pagination and load measurements remain separate gates.
