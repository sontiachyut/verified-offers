# ADR 0004: fail-closed lexical search and replay-safe indexing

Date: 2026-09-15. Status: accepted, incremental implementation.

P3c1 introduces an opt-in local OpenSearch projection and bounded lexical search.
P3c2 remains the separate online rebuild/catch-up/alias-switch acceptance gate.
This clarification supersedes the preliminary pagination and consumer database
deduplication wording in SPEC.md; P3 is not complete after P3c1.

Use OpenSearch 3.8.0, pinned by digest and scanned before adoption. Index full
Offer snapshots using `version_type=external`, never `external_gte`. Only a
version-conflict response is a successful equal/older replay. Keep deletion
snapshots as documents forever in this slice; no physical deletes or automatic
tombstone expiry. A later retention policy must preserve the replay horizon.
Use a strict field mapping, one local shard and no replica (no HA claim).

A single Kafka consumer processes at most one record per poll. Disable automatic
offset commits; commit only after an acknowledged index write/version no-op, or
a durable PostgreSQL quarantine insert. A crash in the projection/commit gap
replays safely through external versions, not an event-ID cache. Schema-v1
envelopes must match their record key and snapshot identity/version/type.
Unknown or malformed events are quarantined by topic/partition/offset with a
fixed reason and SHA-256 digest, not raw data. Quarantine replay tooling and
retention remain future operator work; never silently discard these records.
Transient index/database/broker failures retain the offset for retry. The worker
has bounded polls, network I/O, batch size and a one-second retry delay. It stops
before its index/database dependencies. No payloads or exception details in logs.

`GET /api/v1/search?tenantId=demo&q=keyboard&limit=10` is enabled only with the
search configuration under postgres-local. Tenant is an untrusted local-demo
scope, not authentication. Query length 1–200; limit 1–50, default 10. Retrieve
up to five times limit (at most 250) title matches, filtered by tenant and deletion.
No arbitrary query DSL or unbounded offsets. Cursor requests are rejected until
stable point-in-time pagination is implemented; this is top-N search, not a
complete catalog enumeration or relevance benchmark.

Read candidate identities in one bounded PostgreSQL query (at most 250), then
verify those same snapshots without per-result database calls. Return only exact
snapshot matches that pass the existing five-
minute freshness, price, currency, stock and deletion policy. A changed title,
version or any other field excludes the candidate until indexing catches up.
This deliberately trades recall for safe evidence. Return authoritative Offer,
index version/timestamp and the as-of verification. Fewer than limit is valid;
there is no exact-total claim. The facts share one database statement snapshot;
there is no transaction spanning index retrieval or a checkout guarantee.
Dependency errors fail the request with a generic 503, never an empty success or
a stale-index fallback. Search HTTP I/O and response size are bounded.

Provision a concrete index and a single write/search alias explicitly, not
automatically during API startup. Writes require an alias, so a typo or missing
index cannot silently create an unmapped index. The initial provisioning helper
creates `<alias>-v1`; it is not an online rebuild command. HTTP adapter
uses the JDK client and fixed JSON operations (no additional OpenSearch client
dependency). Local endpoints must be explicit loopback HTTP URLs without userinfo,
paths, queries or fragments. The unauthenticated index is never exposed publicly.

References:
- [External version semantics](https://docs.opensearch.org/latest/api-reference/document-apis/index-document/)
- [OpenSearch 3.8 release](https://github.com/opensearch-project/OpenSearch/releases/tag/3.8.0)

Required evidence: real-index duplicate/out-of-order/tombstone and tenant tests;
stale price/stock/delete/TTL rejection against PostgreSQL; real-Kafka delivery,
poison quarantine, retry and projection/commit-gap replay; packaged HTTP route.
Online rebuild and PIT pagination are explicitly not covered by this slice.
