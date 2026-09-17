# Security boundaries and residual risks

Scope: synthetic local reference system with optional signed-token API mode.
This is a reviewed engineering checklist, not external security certification.

| Boundary / threat | Implemented control | Residual limitation |
| --- | --- | --- |
| Untrusted caller changes tenant or merchant | Verified JWT claims, route/body comparison before storage, separate scopes, signed HTTP tests | No DB row-level tenant isolation; a compromised runtime credential crosses tenants |
| Forged/stale/wrong-service bearer token | RS256, configured issuer/audience, required bounded lifetime and identity, safe 401 | Provider account security, revocation/rotation drill and trusted claim provisioning are external |
| Oversized/ambiguous input | Actual-byte limits, strict typed JSON/NDJSON, fixed schemas/checksum/idempotency | Reverse-proxy/global network DoS controls are not deployed |
| Exhaustion through traffic or cursors | Bounded threads/connections, local tenant buckets, eight page operations, 128 unresolved PITs, fixed expiry | Per-process budgets multiply across replicas; cursors are not HA |
| Old or malicious catalog claims | Versioned immutable source, retained tombstones, every search/proposal reverified | Source merchant truthfulness is not guaranteed; verification is only an as-of fact check |
| Prompt-like text or wrong price extraction | No tools/model/write path; proposals pass the same verifier; experimental flag off by default | Regex semantics are imperfect; matching source price does not prove text meaning |
| Poison events / crash between acknowledgements | Durable quarantine, immutable source reconciliation intent, replay-safe external versions | Operator association must be investigated; arbitrary malformed event repair is not automatic |
| Data leakage via logs/errors/metrics | Fixed errors, no raw paths/queries/tokens/bodies in request logs, bounded metric dimensions | Dependency debug logging must remain off; audit subjects and backups require access controls |
| Runtime credential destroys history/schema | Separate tested runtime/operator groups, no DELETE/TRUNCATE/DDL, immutable triggers | Schema owner is trusted; local default development credential is not automatically restricted |
| Dependency compromise / vulnerable image | BOM/lockfiles, digest-pinned images, exact-revision CI, point-in-time scans | Known image CVEs remain blockers; functional tests do not establish exploitability or remediation |
| Lost database or replay horizon | Independent-container logical restore test, checksums/receipts/lease recovery, fail-closed rebuild | No production PITR, off-site encrypted backup, HA broker, regional recovery or RPO/RTO guarantee |

Never expose the plaintext local broker/index/database or unauthenticated demo
publicly. The optional application container publishes only a loopback host port.
No external credentials, cloud resources or real merchant/customer data were used.

Before shared deployment: patch and scan every target architecture, provision
trusted OIDC claims and rotation, terminate TLS, isolate networks/services, apply
runtime roles and secret handling, set durable retention and encrypted backups,
measure representative load/freshness, and obtain a separate security review.
The repository's tests are evidence for specific controls, not a substitute for
these deployment decisions.
