# ADR 0010: authenticated API without changing local-demo trust

Date: 2026-09-16. Status: accepted for implementation.

Add an opt-in `offers.security.enabled=true` resource-server mode to the existing
loopback profiles. This is not permission to expose the service. The default
local demo stays explicitly unauthenticated; authentication does not manufacture
TLS, HA, database isolation, or a production identity provider.

Use Spring Security's JWT resource server, RS256 signatures, explicit issuer,
audience and JWKS URL. Require expiry, issued-at, subject, tenant identifier and
merchant identifier. Reject expired/future/overlong credentials. Access tokens
carry space-separated scopes: `offers:read`, `offers:write`, `offers:operate`.
Issuer provisioning must prevent callers from choosing tenant/merchant claims.
Read is tenant-wide; mutations and feed investigation are merchant-scoped.
Operator scope controls feed retry/cancel, but never bypasses tenant/merchant.

Resolve identity only from the verified token, never headers, query parameters,
JSON fields or a cursor. Existing scope fields remain for compatibility and are
checked against token claims before accessing storage. Unknown routes are denied
in secured mode; only liveness is anonymous. No cookies, sessions, Basic auth or
form login. CSRF is disabled only because this API accepts header bearer tokens
and never cookie authentication. CORS remains closed. Fixed 401/403 responses
must not echo JWTs, decoder exceptions, tenant existence or keys.

Use generated ephemeral RSA keys and a loopback JWKS server in tests; never
commit private keys. Explicit local HTTP JWKS is acceptable only on loopback;
remote endpoints require HTTPS. Issuer/audience are mandatory, not defaults.
Tests cover signature, issuer, audience, timestamps, scopes and all route scopes.

Reference: [Spring resource-server JWT contract](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Authenticated feed control actions also persist the verified subject and an
authentication marker with the existing immutable action/reason/time record.
The subject is taken from the security context, never the request body. Legacy
and unauthenticated local actions are explicitly labeled `local-operator`, false;
do not retroactively attribute them to a verified identity. Subjects are scoped
audit data, never metric labels or application request-log fields.
