# Current status

Last updated: 2026-09-16 (local time).

## Implemented and evidenced

- P0–P4: specification, Java API/reference model, PostgreSQL immutable source/history
  and transactional outbox, Kafka delivery, replay-safe OpenSearch, stable verified
  pagination, resumable rebuild/handoff, durable merchant feeds and React console.
  Earlier acceptance records remain in `docs/validation/P1.md` through `P4b.md`.
- P5 security: opt-in RS256 JWT issuer/audience/time validation, token-derived tenant
  and merchant authorization, separate scopes, bounded request bodies and tenant
  budgets, safe errors/logs, verified actor attribution on immutable feed actions.
  Real signed HTTP and packaged PostgreSQL isolation tests pass. See
  [authentication](AUTHENTICATION.md) and [threat model](THREAT-MODEL.md).
- P5 baseline evaluation: separately authored synthetic ranking judgments and
  claim development/holdout labels. Actual OpenSearch BM25 nDCG@10 0.87649,
  Recall@10 0.875 on 12 queries. Claim baseline precision 0.80, recall 0.7273,
  with two false proposals; experimental extraction remains OFF by default.
  All raw outputs and error analysis are [published](EVALUATION.md).
- Audited source reconciliation for index quarantine: immutable operator intent,
  saved source version, eight-attempt bound, gate-aware projection and safe replay
  across processes. This is NOT arbitrary raw-event/schema repair. Original
  quarantine evidence is preserved. See [runbook](QUARANTINE.md).
- P6 local operational evidence: dependency readiness distinct from liveness,
  protected Prometheus endpoint, capped pressure metrics, tested restricted
  database roles, independent-container logical restore, and pinned non-root
  read-only application packaging with an actual HTTP smoke test.
- Bounded mixed workload: 250 offers/10 merchants, 200 searches and 40 updates in
  20s, two requests in flight, no observed failures/drops; search-plus-close
  p95 38.18ms on Apple M4/16GiB. This is not the proposed large-scale target or
  a freshness percentile measurement. See [raw load evidence](LOAD.md).
- Successful confirmed PIT deletion now reclaims admission early. Failed/unknown
  opens or deletion acknowledgements retain their expiry/grace reservations,
  preserving the leak bound. Regression includes real-index pagination.
- The owner accepted the UI appearance. Formal viewport/contrast/native browser/
  screen-reader certification is not claimed. The 38 DOM/interaction tests and
  real-API React walkthrough remain separate evidence.
- More than 30 real commits and individual pushes since `1055653`; see the
  [release ledger](COMPLETION.md). No backdating or empty commit padding.

## Current verification checkpoint

The final local backend regression passed **101 unit/HTTP/helper tests plus 85
real-dependency/process tests (186 total, zero failures/errors/skips)**, including
Flyway V8 actor audit, feed admission, restricted-role grant reconciliation and
Tomcat 11.0.26. Console checks passed all 38 focused tests, formatting, TypeScript
and production build; the real-API React walkthrough and four-assertion HTTP demo
also passed. A fixture readiness race was fixed without changing product behavior.

The rebuilt non-root/read-only container smoke passed. Finished-image scanning
identified three critical Tomcat findings, all absent after the patch and rescan:
0 critical, 0 high, 53 medium and 16 low remain on linux/arm64. Dependency-image
findings remain separate deployment blockers; no findings were suppressed.
See [final local evidence](validation/RELEASE-2026-09-16.md) and the
[before/after scan](validation/APPLICATION-IMAGE-SECURITY.md).

CI repeats backend, console, real-API and hardened-container gates on every push.
The exact revision's result is maintained in GitHub Actions, not inferred from a
previous or cancelled run. Heavy local checks ran sequentially with bounded JVMs;
no paid infrastructure or inference was used.

## Exact next gate

The implemented local release checks are complete. Verify the exact pushed SHA's
[CI result](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml)
before handoff. Next, obtain the owner's environment/resource/security choices
for representative sustained-load testing and any shared deployment. Do not run
the proposed stress workload on a hot laptop or create paid/public resources.
The original specification is not 100% production-ready; the remaining gates
below are not replaced by the local acceptance results.

## Operational boundaries

- `postgres-local` is durable; `local-demo` is volatile. Both are loopback-only
  and unauthenticated by default. JWT mode is optional, not a production provider
  integration. The console is still a local demo client, not an OIDC login UI.
- Publishing/search/indexing/feeds and background feed work are separately opt-in.
  Extraction is experimental and separately opt-in; no paid model integration.
- Images have documented vulnerability findings. Finished-app and dependency
  scans are point-in-time/platform-specific; pinned images are not clean-image
  or exploitability claims. Public deployment is not approved.
- Retain source versions, tombstones, outbox/feed/quarantine/audit evidence
  indefinitely in this bounded reference system. No destructive cleanup is
  implemented. Feeds retain 100 jobs globally and 20 actions/job; snapshots retain
  ten jobs with 100k offers/job. These caps are not an unbounded production
  lifecycle policy. A reviewed archival design remains future work.
- Search state/cursor keys and quotas are process-local, not HA. Eight page
  operations and 128 unresolved reservations; fixed two-minute cursor lifetime.
  Only acknowledged PIT deletion releases reservations before the grace deadline.
- Handoffs require gate-aware indexers, at most 10k replay offsets/32 partitions,
  no foreign/transactional/compacted writers or concurrent manual alias changes.
  SWITCHING recovers forward; do not blindly roll back.
- Database roles are tested but not applied automatically to the simple local
  profile. Production credentials, TLS, network isolation, rotation, encrypted
  off-site backup/PITR and measured RPO/RTO remain deployment work.
- Representative 100k-offer/100-search-per-second/20-update-per-second testing,
  freshness percentiles, HA, OTLP/collector/dashboard deployment and formal
  browser/accessibility review remain open. The laptop run does not replace them.
- No cloud resources or external messages were created. Resume using this file,
  ROADMAP.md, COMPLETION.md and ADRs 0010–0012 plus the relevant earlier ADR.
