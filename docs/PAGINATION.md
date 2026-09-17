# Stable, verified search pages

Use the local stack and search-enabled API from [SEARCH.md](SEARCH.md).
Synthetic data only: tenant IDs are demo scope, not authorization. There is no
public deployment, large-scale measurement or checkout-price guarantee.

## Request and continue

```sh
curl --fail-with-body -sS --get http://127.0.0.1:8081/api/v1/search \
  --data-urlencode 'tenantId=demo' --data-urlencode 'q=keyboard' \
  --data-urlencode 'limit=2' | jq
```

Alongside `results`, responses include:

| Field | Meaning |
|---|---|
| `nextCursor` | Opaque continuation token, or null when this search is complete. |
| `expiresAt` | Fixed application deadline, two minutes from search admission. Requests do not renew it. |
| `scanLimitReached` | The 10,000-candidate examination cap was reached. No further continuation is provided; this does not assert whether more matching documents exist. |

Copy `nextCursor` into `cursor` on the next request. Keep tenantId, q and limit
the same; leading/trailing query whitespace is normalized, other changes fail.

```sh
curl --fail-with-body -sS --get http://127.0.0.1:8081/api/v1/search \
  --data-urlencode 'tenantId=demo' --data-urlencode 'q=keyboard' \
  --data-urlencode 'limit=2' --data-urlencode 'cursor=<returned-token>' | jq
```

Do not decode/edit tokens or send OpenSearch PIT IDs. The token is authenticated,
not encrypted, and is not a login credential. Clients should treat it as opaque.
Do not log cursor values or put them in shared links. There is no exact total.

**Continue based on nextCursor, not the number of results.** An empty verified
page can have a continuation: it examined a bounded batch whose offers were
stale, changed, unavailable or deleted. Each request reads at most 5 × limit
index candidates (250 maximum), then checks them in one PostgreSQL batch.
Candidates fetched beyond the last examined item are not skipped.

## Stable order is not frozen truth

The index PIT freezes candidate membership and ranking. Sort order is relevance
score descending, then merchant/offer identity ascending to break ties. New
indexed offers, index refreshes and alias handoffs do not shuffle that snapshot.

PostgreSQL verification is deliberately current on **every** page. If an offer's
price, version, stock or any source field changed after the PIT was opened, it
is excluded; tombstones and expired facts are also rejected. A new search is
required to see newer candidates. Reusing a cursor while its session is open is
allowed, but current verification may change the returned subset over time.
No distributed transaction spans the index, database and shopper checkout.

## Release a search early

If the user abandons an unfinished search, send its latest cursor with the same
scope to DELETE. Example:

```sh
curl --fail-with-body -sS --get -X DELETE http://127.0.0.1:8081/api/v1/search \
  --data-urlencode 'tenantId=demo' --data-urlencode 'q=keyboard' \
  --data-urlencode 'limit=2' --data-urlencode 'cursor=<returned-token>' | jq
```

This closes the application session and best-effort deletes its PIT. Exhaustion
or scan-budget completion does this automatically. Older cursors for a completed
or closed session return 410, including a repeat DELETE. If the backend cannot
acknowledge deletion, its fixed expiry is the fallback; no lifetime is extended.

## Errors and resource bounds

- `400`: invalid bounds/token or changed tenant/query/limit. Correct the request;
  never silently replace a continuation with a new search.
- `410`: closed, expired or process-restart-lost session. Start a new search
  deliberately; results can differ from the previous snapshot.
- `429`: capacity exhausted or another request is already using this session.
  Retry later. Page requests are not queued without a bound.
- `503`: a dependency failed, the index returned partial/invalid data, or the PIT
  backend context was lost. A temporary outage can be retried with the same
  cursor before expiry; the server never opens a replacement PIT for that cursor.

The local API admits at most 128 unresolved snapshot reservations per process and
eight in-flight requests. Confirmed successful backend PIT deletion releases its
reservation early. Failed/partial/unknown deletion or open acknowledgements retain
the reservation for two minutes plus ten seconds of I/O grace, so repeated failures
can still produce 429 despite no visible PITs. HTTP 200 alone is insufficient:
every requested PIT ID must have an explicit successful deletion acknowledgement.
Abandoned backend PITs expire without renewal; expiry cleanup needs no worker.

Cursors and their signing key are intentionally process-local. Restart requires
a fresh search. Opt-in JWT mode authorizes the tenant before cursor access;
multiple API instances, durable cursor recovery and distributed quotas are not
implemented. Backend node-level
PIT limits remain relevant, including contexts left briefly by a killed process.

See [ADR 0007](adr/0007-stable-search-pages.md) for the design and
[acceptance evidence](validation/P3c3.md). Do not delete an old index manually
while a PIT may still reference it. Use the normal non-destructive stack shutdown
when finished; no global cleanup command is required.
