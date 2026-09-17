# Search and feed investigation console

A local React/TypeScript client for the existing APIs. The browser is not a
second verifier. Synthetic data only; tenant and merchant inputs are untrusted
scope, not authentication. Nothing in this console authorizes public deployment.

## Start the interface

Use Node 22.22.2 and npm 11.11.0 (versions used in CI). From the repository root:

```sh
cd console
npx --yes npm@11.11.0 ci --no-fund
npm run dev
```

Open the printed loopback URL, normally `http://127.0.0.1:5173/`. Docker is not
needed to inspect the interface or run its focused tests. Without a backend,
requests display an error; no fabricated results replace the missing service.

If the system Node is unsupported, use a scoped runtime without replacing it:

```sh
npx --yes --package=node@22.22.2 --package=npm@11.11.0 -- npm ci --no-fund
npx --yes --package=node@22.22.2 -- npm run dev
```

The [Vite development proxy](https://vite.dev/config/server-options#server-proxy)
forwards `/api` to `http://127.0.0.1:8081`. The built bundle can be exercised with
`npm run build && npm run preview` on loopback port 4173 using the same proxy.
Both servers refuse a busy configured port. Neither is a public production host.
No backend CORS relaxation, cloud database, remote fonts, telemetry or credentials
are added. Do not bind either service to a LAN/public interface.

## Connect the existing backend

Follow [SEARCH.md](SEARCH.md) for PostgreSQL/Kafka/OpenSearch, explicit topic/alias
setup and the search-enabled API. Add both feed flags to its Java command:

```text
--offers.feeds.enabled=true --offers.feeds.worker-enabled=true
```

All workers remain independently opt-in. For feed-only investigation, the
[feed runbook](FEEDS.md) needs only PostgreSQL; the search view will report the
disabled route. If the feed worker is off, admitted jobs stay queued. Liveness
does not prove that dependencies, indexing or processing are ready.

## Search workflow

1. Select a demo tenant, enter a title query and choose a page size. Press Enter
   or **Search offers**. There is no automatic request on every keystroke.
2. Inspect a returned offer. Evidence exposes authoritative/index versions,
   original source timestamps and `verifiedAt`. Integer prices/versions and
   microseconds are preserved. Displayed evidence is historical, not continuously
   reverified or a checkout/stock guarantee.
3. **Continue search** follows the same snapshot and original scope even if form
   fields are edited. An empty page can still be continued. Loaded count is not
   an exact catalog total. No cursor is put into browser storage or shared links.
4. Expiry/410 requires an explicit new search. Transient failures offer a retry
   of the same cursor. New searches/navigation close abandoned cursors best-effort;
   fixed server expiry handles lost responses and tab shutdown.

## Feed workflow

1. Open a tenant/merchant scope. The job list is a bounded live UUID-keyset view,
   not creation-order sorting; refresh deliberately to discover newer jobs.
2. Select a UTF-8 NDJSON file, source label and idempotency key. The console bounds
   file size before reading and hashes/sends the exact bytes without parsing or
   rewriting timestamps/newlines. Server validation remains authoritative.
3. An uncertain response freezes the attempt. **Retry identical upload** preserves
   file bytes, checksum, source, scope and key. Keep the tab open until resolved;
   request bytes are intentionally not persisted in browser storage. Starting a
   different attempt requires explicit acknowledgement. Navigation within the
   console preserves an unresolved attempt.
4. Admission opens the job investigation. Refresh manually for counters, row
   receipts and local action history; no polling or invented processing ETA.
   A completed feed only proves source/outbox commits, not search visibility.
5. PAUSED jobs can resume pending rows after the dependency is repaired. Rejects
   need a corrected new feed, not a retry button. Cancel only pending work; applied
   rows and outbox events remain. Both actions require a reason and confirmation.
   An uncertain action locks further actions until a successful refresh.

The [mixed example](../examples/feed-mixed.ndjson) uses `demo/synthetic`. Its old
source times intentionally do **not** turn fresh when uploaded. See FEEDS.md for
expected two-applied/one-replayed/one-rejected outcomes in a fresh scope.

## Verification and resource use

```sh
# From console/: no Docker, one test worker, no watch loop
npm run check

# From repository root: real backend acceptance gate; Docker required
./mvnw --batch-mode --no-transfer-progress verify

# From console/, after the packaged Java jar exists
npm run test:integration
```

The integration harness creates a randomly named, isolated synthetic Compose
project using the root's pinned images. It needs unoccupied loopback ports 5541,
9094 and 9201 and never adopts an existing stack. It starts a bounded Java process
and temporary Vite proxy, drives React controls in jsdom against the real APIs,
then removes **only its own disposable synthetic containers/volumes**. Do not
put wanted data into those test resources. It does not quit Docker itself.

The walkthrough checks upload admission, real atomic row outcomes, idempotent
recovery, asynchronous Kafka/index delivery, two search pages, source evidence
and immediate source-deletion exclusion. The browser FileList facade is an
explicit jsdom limitation: tests dispatch the upload form after asserting the
selected file. Hashing, HTTP, Vite proxy, worker and dependency behavior are real.

Run heavy checks sequentially on a laptop. Stop API processes before stopping
their dependencies. Stop the frontend with Ctrl-C; quit Docker when no other
session needs it. No background service/autostart is installed by the console.

DOM accessibility checks cover landmarks, control names and keyboard actions.
They are not visual QA or WCAG certification. Real-browser viewport, contrast,
native file selection and dialog focus trapping still require a connected browser
and an explicit browser-testing pass. The implementation uses native dialogs,
visible focus, semantic tables, a skip link and a narrow-screen CSS layout.

Architecture and tradeoffs: [ADR 0009](adr/0009-investigation-console.md).
