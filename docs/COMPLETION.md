# Release acceptance ledger

Started 2026-09-16 from `1055653`. This is an evidence checklist, not a promise
of production readiness. Commits record actual work at actual times. Each
milestone is pushed independently; no empty commits or manufactured history.

## Remaining release gates

- [x] Signed JWT authentication, issuer/audience/time validation, safe failures
- [x] Tenant and merchant object authorization across existing HTTP routes
- [x] Separate read, write and operator scopes; deny-by-default routing
- [x] Authenticated HTTP isolation tests with real signatures
- [x] Bounded request bodies and per-tenant request admission
- [x] Safe request correlation and bounded operational metrics
- [x] Documented identity-provider configuration and trust boundaries (external provider drill remains deployment work)
- [ ] Audited, bounded index-quarantine resolution without trusting poison data
- [ ] Recovery tests covering retries, failures and audit preservation
- [ ] Explicit retention decisions that preserve replay and forensic evidence
- [ ] Independently labeled lexical relevance dataset and reproducible evaluation
- [ ] Claim-extraction baseline with held-out labels and error analysis
- [ ] No AI quality claim without measured comparison; no paid inference by default
- [ ] Dependency readiness distinct from liveness
- [ ] Reproducible, bounded load experiment with raw results and hardware metadata
- [ ] Backup/restore drill against real PostgreSQL
- [ ] Least-privilege database role verification
- [ ] Non-root, pinned application packaging and vulnerability review
- [ ] Operational runbooks, release checklist and current architecture narrative
- [ ] Full Java, console, dependency integration and CI regression on release HEAD

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

Further entries are appended with the implemented behavior and focused evidence.
