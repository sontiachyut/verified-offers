# ADR 0009: local evidence-first investigation console

Date: 2026-09-16. P4b implementation contract.

Add an independently built React/TypeScript/Vite client in `console/`. Preserve
the existing Java/PostgreSQL authority and loopback-only deployment boundary.
The development and preview servers proxy `/api` to 127.0.0.1:8081; do not open
backend CORS or introduce a hosted database, authentication facade or public
deployment. No social previews are needed for this non-shareable local tool.

Use two task-oriented views: verified search and merchant feed investigation.
Restrained ink/blue styling, semantic tables, native controls, visible keyboard
focus and responsive layouts take priority over decorative dashboard metrics.
All scope fields are explicitly untrusted local-demo scope. Do not infer
dependency readiness from liveness or invent metrics, results or progress.

Search freezes tenant/query/limit for each cursor chain. Show server-returned
source/index versions, source times and verifiedAt as historical evidence, never
as a live guarantee. Empty pages with a cursor remain continuable. Keep opaque
cursors only in memory. Close abandoned searches best-effort, including a late
response after unmount; fixed server TTL remains the fallback. Do not silently
restart on 410/503. Timeouts and dependency failures are visible and retryable.

Feed upload hashes and transmits the exact selected bytes. Bound before reading
to 1 MiB; server remains authoritative for schema, scope and row limits. Freeze
bytes/scope/source/idempotency key for an attempt, including uncertain network
outcomes. Retrying resends the same request. A new upload requires an explicit
reset, with an uncertainty warning. No raw invalid feed bodies are displayed.

Job lists, row receipts and local action records use bounded API pagination.
Refresh is explicit: no background polling or inferred ETAs. Label loaded data
as a snapshot. Retry is available only for PAUSED, cancellation only unfinished
jobs. Both need a confirmation and bounded reason identifier. Cancellation
preserves committed rows; retry resumes pending rows, not rejected ones. After
an uncertain action, require a status refresh before any further mutation.

Do not round Java long money/versions through unsafe JavaScript numbers: parse
integer JSON values losslessly and format integer USD cents without floating
arithmetic. Keep comparison/verification policy on the server.

Acceptance: TypeScript/build, focused low-concurrency component/API tests,
loading/empty/error/expiry/continuation, exact-byte retries, scoped navigation,
confirmation/keyboard behavior, synthetic API walkthrough and full Java gate.
Browser visual testing is separate and requires owner opt-in in this session.
