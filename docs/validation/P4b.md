# P4b console evidence

Date: 2026-09-16 (America/Phoenix). Synthetic local reference implementation.

## Exercised client behavior

- 37 focused tests: search idle/loading/empty/error states; empty-page cursor
  continuation; frozen tenant/query/page size; 503 same-cursor retry; explicit
  410/local expiry; unmount/late-response cleanup; keyboard form submission;
  untrusted text escaping; exact Java long prices/versions and source microseconds.
- Feed tests: scoped job and receipt pagination; explicit refresh; sanitized
  reasons; progress semantics; immutable fingerprints; exact CRLF byte hashing;
  rejection before reading oversized files; uncertain-response identical retry;
  discard confirmation; navigation preservation; safe fixed HTTP error copy.
- Operator tests: confirmation before mutation, reason payload, partial-commit
  cancellation wording, terminal controls, uncertain-action refresh interlock,
  dialog focus return and failed-refresh protection.
- axe-core checks on both initial workspaces find no DOM-rule violations, with
  color contrast explicitly excluded because jsdom has no layout engine.
  This is not complete WCAG certification or a real-browser accessibility audit.
- TypeScript, formatting and Vite build pass. The npm audit at installation
  reported zero known vulnerabilities; that is time-bound, not a security promise.

## Real dependency walkthrough

`cd console && npm run test:integration` passed against a newly created,
randomly named Compose project using the root's pinned PostgreSQL, Kafka and
OpenSearch images. No existing stack/data was adopted.

The harness launches the packaged Java API with feed/publisher/indexer enabled
and a dynamic loopback port. A temporary Vite instance uses the actual proxy
configuration to reach it. React controls are driven in jsdom over real HTTP:

1. Open a unique synthetic tenant/merchant scope.
2. Submit eight CRLF NDJSON rows: six distinct offers, one replay and one invalid
   JSON row. SHA-256 covers the exact uploaded bytes.
3. Observe COMPLETED_WITH_ERRORS, eight resolved receipts, six APPLIED, one
   REPLAYED and one INVALID_JSON rejection; invalid raw data is not exposed.
4. Repeat the exact request identity and recover the same durable job.
5. Wait explicitly in the test for asynchronous delivery/index refresh. Search
   in the UI and continue a five-result page to obtain the sixth offer.
6. Inspect server-returned source/index evidence and exact USD formatting.
7. Write a higher-version source tombstone and immediately search again; the
   deleted candidate is not offered, regardless of indexing delay.

The test passed in 3.51 seconds after dependencies/API startup; this is test
runtime, **not a throughput or latency benchmark**. The harness shuts down its
API/proxy and removes only its disposable synthetic containers and volumes.
The full backend gate remains separate from this test.

The final `./mvnw --batch-mode --no-transfer-progress verify` run also passed:
59 unit/HTTP/helper tests plus 75 real dependency/process integration tests,
zero failures/errors/skips. It ran at lower OS scheduling priority with
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m'`, taking 2:58 locally.
The four-assertion HTTP demo passed afterward. The final frontend check reran
all 37 tests, formatting, TypeScript (including integration sources) and build.
CI now repeats the client checks and the real-dependency console walkthrough
in addition to the backend suite; inspect the exact pushed revision's result.

## Explicit limits

- No connected browser was available in this session. Visual viewport/contrast
  review and native file-dialog/focus-trap checks were not performed. Responsive
  CSS and keyboard/DOM tests are implemented, but must not be mislabeled as a
  completed visual audit.
- jsdom's user-event FileList facade does not satisfy its internal native
  required-file constraint. Tests assert the selected file and other native
  constraints, then dispatch submit. Application validation is not removed.
- Job status, receipt pages and operator actions are separate API snapshots,
  not one cross-endpoint transaction. The UI labels manual refresh/as-of state.
- Closing/reloading a tab discards an unresolved upload's in-memory bytes/key;
  the UI warns while an admitted outcome is unresolved. Reconcile deliberately.
- Local untrusted scope, no identity/auth, no cloud hosting, no traffic/scale
  claim, and all previously documented container/security gates remain.

See [console runbook](../CONSOLE.md) and [ADR 0009](../adr/0009-investigation-console.md).
