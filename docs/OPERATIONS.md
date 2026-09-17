# Local operations and alerting

No unattended public deployment is approved. Keep all listeners loopback-only,
use synthetic data, and run heavy tasks one at a time. Do not run load generators
alongside recovery tests or image scans on a thermally constrained laptop.

## Health contracts

`GET /actuator/health` checks application liveness, not database/search/broker
readiness or bounded lag. `GET /api/v1/readiness` (postgres-local) checks PostgreSQL,
the configured write alias if search is enabled, and the topic/leader for each
enabled publisher/indexer Kafka connection. 200 READY or 503 NOT_READY contains
only fixed dependency UP/DOWN states. It does not verify consumer progress,
freshness SLA, replica health, schema authorization or end-to-end delivery.
Secured mode requires `offers:operate`; do not poll rapidly. Per-probe timeouts
are bounded, but consecutive failed dependencies can make this a slow request.

## Metrics

`GET /actuator/prometheus` exposes Prometheus text. JWT mode requires
`offers:operate`; the unauthenticated demo remains local-only. Never publish this
endpoint on the internet. No Prometheus/Grafana server is auto-started.

- `offers_http_requests_seconds_*`: duration/count by bounded operation and status
  class; no tenant, user, offer, query, cursor or raw-path dimensions.
- Existing `offers_publisher_*` / `offers_indexer_records_total` and feed worker
  counters distinguish successful work, retry and idle outcomes.
- `offers_outbox_pending_capped`, `offers_outbox_quarantine_capped`,
  `offers_index_quarantine_capped`: at most 10,001; this value means at least
  10,001, not an exact higher count. Feed pending gauge saturates at 101.
- A dependency query failure returns NaN, never a false healthy zero.
- Standard JVM/process metrics support resource investigation.

Suggested alerts are hypotheses, not tuned production SLOs: sustained 5xx ratio,
repeated readiness failure, increasing pending work without successful delivery,
any new quarantine record, and feed jobs paused without operator acknowledgement.
Use a 15–30s scrape interval during manual inspection. Stop scraping when done.
Counters alone do not prove absence of dropped work; inspect source and receipts.

## Incident sequence

1. Preserve generated request IDs, bounded status/metrics and exact deployed commit.
   Never paste credentials, payloads, private URLs or raw tokens into public issues.
2. Check dependency readiness and worker enable flags. Keep source writes separate
   from projection failure: a saved feed/outbox receipt is not search visibility.
3. For source/search disagreement, fail closed; use SEARCH.md, HANDOFF.md and
   QUARANTINE.md. Never bypass verification or physically delete tombstones.
4. For feed failures, inspect row evidence and retry only documented retryable
   jobs with a reason; cancellation does not undo committed rows.
5. Capture and validate a backup before any destructive recovery proposal. Restore
   into an isolated target and review evidence before replacing a live service.

Request logs contain generated ID, fixed operation, status and duration. They
support correlation but are not a complete actor-level compliance audit. Feed and
reconciliation records retain domain operator evidence separately. Central log
collection/retention, tracing export and production alert routing remain explicit
deployment work, not something provided by a local metrics endpoint.
