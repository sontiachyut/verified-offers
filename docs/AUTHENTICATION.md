# Authenticated local API

The console remains a loopback-only, unauthenticated demonstration. JWT mode is
an API integration mode, not a browser login implementation or public deployment
approval. Do not put bearer credentials into console source, URLs or localStorage.

Enable alongside `postgres-local` (or `local-demo` for volatile security tests):

```text
--offers.security.enabled=true
--offers.security.issuer=https://YOUR-IDENTITY-PROVIDER/issuer
--offers.security.audience=verified-offers
--offers.security.jwks-uri=https://YOUR-IDENTITY-PROVIDER/jwks
```

These are examples, not a provisioned provider. Issuer and JWKS must be trusted
operator configuration. Remote URLs require HTTPS; explicit loopback HTTP is
allowed for synthetic integration tests. JWKS network calls have 2s connect/read
timeouts. The identity provider must issue RS256 access tokens and control claims:

| Claim | Contract |
| --- | --- |
| `iss`, `aud` | Exact configured issuer; configured audience is a member |
| `sub` | Nonblank, at most 200 characters |
| `iat`, `exp` | Required; no future issuance, positive lifetime at most one hour |
| `nbf` | If supplied, must not be in the future |
| `tenant_id`, `merchant_id` | Required 1–64-character application identifiers |
| `scope` | Space-separated explicit permissions below |

There is no application clock-skew grace: an expired token is rejected. Keep
identity-provider and API clocks synchronized. Never let self-service clients
assign tenant/merchant claims. Key rotation uses the resource server's JWKS cache;
emergency revocation requires provider/operational controls, not a local denylist.

`offers:read` permits same-tenant catalog/search/verification and own-merchant feed
inspection. `offers:write` permits own-merchant ingestion/uploads.
`offers:operate` permits own-merchant feed retry/cancel. Permissions do not imply
one another. Operational CLI access is a separate OS/database permission boundary.
Every body/path/query scope is compared against verified identity before storage.
Cursors are not credentials. Only `/actuator/health` is anonymous in secured mode.
Unknown routes are denied. No cookie, session, Basic or form authentication.

## Abuse and forensic boundaries

Authenticated tenants receive a process-local 60-request burst and 30 requests/s
refill, shared across API operations; 1024 active tenant budgets maximum. A full
budget table fails closed for new tenants until an idle bucket expires after 60s.
429 includes `Retry-After: 1`. This is not a fleet-wide quota; replicas multiply
the budget. The loopback-only demo does not imply internet DoS protection.

JSON API bodies are capped at 16KiB; feed uploads at 1MiB, including chunked bytes.
Encoded bodies are rejected. HTTP headers, connections, threads and upload waits
are bounded. Every API response gets a generated request ID and no-store policy.
Operational logs contain only generated request ID, bounded operation category,
status and duration. No tokens, bodies, paths, queries or identity identifiers.

Tests generate ephemeral keys in memory. `AuthenticatedApiTest` exercises real
HTTP/signature validation; `ApiAccessTest` and `FeedAuthorizationTest` check
authorization order. A production identity-provider onboarding/rotation drill,
TLS gateway, distributed quotas and external security review remain deployment
work; no synthetic test is represented as those integrations.
