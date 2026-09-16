# ADR 0007: bounded point-in-time search pages

Date: 2026-09-16. Implemented for the bounded local P3c3 gate.

Use OpenSearch PIT plus search_after, not offsets or a new live query per page.
Sort by score descending, merchantId ascending, offerId ascending; the latter
two fields uniquely identify a document inside the tenant filter. Keep the PIT
for two minutes without renewal. Alias changes and newly indexed offers do not
alter its candidate set. Current PostgreSQL facts are still rechecked on every
page: snapshot membership is stable, VERIFIED eligibility is intentionally not.

Each request reads at most 5 × limit candidates (250 maximum) and one database
batch. Stop at limit VERIFIED results, advancing after the last **examined** hit,
not the last fetched hit. If a batch yields no eligible offers, an empty page
may still carry nextCursor. Never infer completion from results.length. Limit
each cursor chain to 10,000 examined candidates and report that bound explicitly.

The public response adds nextCursor, expiresAt and scanLimitReached to results.
Opaque HMAC-signed cursor positions bind to an in-process session holding tenant,
normalized query, limit, PIT and fixed expiry. Raw PIT IDs are never accepted
from clients. Malformed/tampered or mismatched-scope tokens return 400; expired,
closed or process-restart-lost sessions return 410. Index/dependency failures
return generic 503, never transparently create a replacement PIT. Tokens are
not authentication; this remains loopback-only and uses untrusted demo tenants.
DELETE on the same route accepts the cursor with its original scope and closes
the session; repeat use (including repeated DELETE) returns 410. A well-shaped
token naming an unknown session returns 410 before signature verification, so
tokens from a previous process do not misleadingly suggest a fresh snapshot.

Local resource admission allows 128 session reservations per process and eight
in-flight page requests. Reservations survive completion/open failure until the
fixed lifetime plus a ten-second I/O grace; this also bounds leaked contexts if
an open/close response is lost. Successful completion or explicit DELETE releases
the PIT early, but not its admission reservation. Abandoned PITs expire without
renewal. Shutdown makes one bounded best-effort delete call for owned PITs.
This is intentionally conservative, not a distributed quota or HA cursor store.
Concurrent operations on the same session fail with 429 rather than queue.
Restart invalidates cursors; multiple API instances/sticky routing are unsupported.

Cursors can be retried while the session remains open, but verification outcomes
can change with source facts/time. Exhaustion/explicit close makes older cursors
unusable. A transient page failure retains its session for retry until expiry.
No exact total, checkout guarantee, load claim or new authorization is implied.

Acceptance: real PIT iteration across refreshes/source updates/deletions/alias
handoff; stable tie ordering; no skipped prefetched candidates; empty verified
pages; scope/tamper/expiry/resource limits; retry after dependency failure;
packaged HTTP continuation/close; full regression gate.

References: [PIT semantics](https://docs.opensearch.org/latest/search-plugins/searching-data/point-in-time/)
and [pagination](https://docs.opensearch.org/latest/search-plugins/searching-data/paginate/).
