# Release acceptance ledger

Started 2026-09-16 from `1055653`. This is an evidence checklist, not a promise
of production readiness. Commits record actual work at actual times. Each
milestone is pushed independently; no empty commits or manufactured history.

## Local release gates

- [x] Signed JWT authentication, issuer/audience/time validation, safe failures
- [x] Tenant and merchant object authorization across existing HTTP routes
- [x] Separate read, write and operator scopes; deny-by-default routing
- [x] Authenticated HTTP isolation tests with real signatures
- [x] Bounded request bodies and per-tenant request admission
- [x] Safe request correlation and bounded operational metrics
- [x] Documented identity-provider configuration and trust boundaries (external provider drill remains deployment work)
- [x] Audited, bounded index-quarantine source reconciliation without trusting poison data
- [x] Recovery tests covering retries, failures and audit preservation
- [x] Explicit retention decisions preserving evidence: indefinite local retention with hard admission caps; lifecycle automation remains open
- [x] Separately authored synthetic relevance labels and reproducible actual-index evaluation (not external human annotation)
- [x] Claim-extraction baseline with held-out labels and published error analysis
- [x] No AI quality claim without measured comparison; no paid inference by default
- [x] Dependency readiness distinct from liveness
- [x] Bounded load experiment with 240 raw samples and hardware metadata; representative target still unproven
- [x] Independent-container backup/restore drill against real PostgreSQL
- [x] Restricted database runtime/operator role verification using a non-owner LOGIN
- [x] Non-root, pinned application packaging and isolated HTTP smoke test
- [x] Finished-image vulnerability scan and documented remediation/review; remaining findings are not waived
- [x] Operational runbooks and threat model
- [x] Full local Java, console and real-dependency regression, plus packaged-image smoke

CI repeats these gates on every push, including the hardened-container smoke.
Its exact-SHA result is external evidence in
[GitHub Actions](https://github.com/sontiachyut/verified-offers/actions/workflows/ci.yml),
not a manually checked box. Require a successful run for the handoff revision;
cancelled or older runs do not satisfy that gate. See the
[final local evidence](validation/RELEASE-2026-09-16.md).

Existing evidence remains in `validation/P4b.md` and earlier validation records.
The owner accepted the console appearance; this is not automated browser or
screen-reader certification.

## Boundaries that cannot be silently checked off

Public deployment, paid cloud or model inference, real merchants and secrets
require separate owner approval. Hardware-limited load results must report the
actual workload, not imply the proposed 100k-offer/100-search-per-second target
was reached. Unresolved image vulnerabilities remain explicit deployment gates.
Optional multi-node/cloud experiments are not local correctness evidence.

## Milestone record

1. Release acceptance ledger: establishes the remaining gates before code changes.
2. Token policy: mandatory bounded identity/times/audience, with 9 focused policy
   and existing HTTP tests passing; explicit unauthenticated local filter chain.
3. Catalog/search authorization: five policy tests plus existing HTTP regression.
4. Feed authorization: every feed route rejects before touching the store.
5. Signed JWT resource server: eight real HTTP tests with ephemeral RSA/JWKS;
   wrong signature, issuer, audience, scope, identity and expiry fail closed.
6. Body/connection limits: JSON/feed budgets, chunked bytes and encoding tests.
7. Tenant admission: bounded buckets, refill/isolation/concurrent-spend tests.
8. Non-web CLI startup: HTTP security does not require servlet beans or issuer.
9. Safe telemetry: generated request IDs, bounded metrics, authentication runbook.
10. Packaged authenticated feed test against PostgreSQL: own-scope reads/cancel,
    cross-tenant/merchant denials and exactly one authorized audit action pass.
    Corrected telemetry test resource cleanup; focused telemetry/JWT tests pass.
11. Immutable quarantine reconciliation schema and documented source-trust boundary.
12. Durable reconciliation engine: replay/idempotency, preserved history, finite retries.
13. Exact-option operator CLI and recovery/retention runbook.
14. Packaged reconciliation across JVM processes and real index tombstone evidence.
15. Frozen synthetic development/holdout labels and graded ranking judgments.
16. Corrected live-index evidence assertion (shadow-only helper was inappropriate).
17. Bounded deterministic USD proposal extractor and exact evidence spans.
18. Opt-in extraction API routes every proposal through source verification.
19. Reproducible holdout and actual OpenSearch BM25 evaluation with tested metrics.
20. Published all evaluation outputs, errors and experimental-mode decision.
21. Configured-dependency readiness distinct from liveness, including outage test.
22. Operator-only Prometheus endpoint, capped pressure gauges and incident runbook.
23. Separate database capability groups, real-login restrictions and setup guide.
24. Independent-container logical backup/restore with receipts, triggers and leases.
25. Raw restore evidence and non-destructive recovery procedure.
26. Successful-PIT-close capacity reclamation; uncertain acknowledgements retain bounds.
27. CI superseded-run cancellation and exact-commit evidence artifacts.
28. Explicit mixed-load experiment with bounded arrivals/concurrency and raw samples.
29. Pinned non-root image, read-only local packaging and isolated HTTP smoke test.
30. Published 200-search/40-update observation and explicit scale limitations.
31. Immutable feed-control audit now records verified actor subject, never a caller-supplied label.
32. Preserved feed semaphore admission before streaming upload bytes.
33. Consolidated implemented behavior, threat model and unresolved release risks.
34. Updated phase status and acknowledged-PIT-deletion lifecycle documentation.
35. Aligned local walkthroughs with optional identity and actor attribution.
36. Patched embedded Tomcat and added isolated digest-pinned image scanning.
37. Added hardened-image build and HTTP smoke to the CI acceptance job.
38. Made database grants converge on the allowlist and reject privileged groups.
39. Published unsuppressed before/after image scans: critical findings 3 to 0.
40. Fixed the real-API console fixture to require a continuable indexed snapshot.

Every numbered milestone above corresponds to a real commit after `1055653`;
use `git log --reverse --oneline 1055653..HEAD` for exact hashes and dates.

## Not silently converted into completed gates

Representative 100k-offer/100-search-per-second/20-update-per-second testing,
index-freshness percentiles, retention automation, distributed cursor/quota state,
formal browser/screen-reader review, OTLP/collector/dashboard deployment, production
OIDC/TLS/secrets/network setup, patched dependency images on every architecture,
and production RPO/RTO/HA are not proved by this local release. The original spec
is not “100% production-ready.” Public deployment and paid resources still need
owner choices and authorization. Optional model work requires a new evaluation.

Further entries are appended with the implemented behavior and focused evidence.
