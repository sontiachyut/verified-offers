# ADR 0002: PostgreSQL catalog transactions

Status: accepted for P2 local persistence. Date: 2026-09-08.

Use an identity lock row per (tenant, merchant, offer). INSERT ON CONFLICT DO NOTHING handles simultaneous first creation; SELECT FOR UPDATE serializes updates to that identity. Under READ COMMITTED, a second statement sees the winner's committed head.
Store immutable offer_version rows with canonical payload hash and source/receipt timestamps. offer_head references exactly one persisted version. A higher version, new head and outbox envelope commit together. Same-version identical replay writes nothing; changed payload or lower version returns conflict.
A database trigger rejects UPDATE/DELETE of history. This is application-level immutability, not protection against a database administrator. Local migration credentials are not a production least-privilege role.

Normalize source Instants to microseconds in the domain record before equality, hashing and persistence. PostgreSQL timestamps have microsecond precision; silently rounding only in the DB would break retries. Version remains the ordering authority even if source times move backward; stale data does not become fresh on receipt.
Share one deterministic verification policy between the volatile and JDBC adapters. Capture verification time after the read; responses are as-of statements, not guarantees at checkout.
Canonical hash v1 is SHA-256 of a fixed-order JSON array of identity and typed offer fields (UTC source Instant string). Hash format changes require an ADR/migration; JSONB's key order is not the hash authority.
Keep full outbox envelopes in PostgreSQL now. Kafka publication/leases/retry semantics remain P3.

The postgres-local profile is durable but loopback-only and unauthenticated. Verified tenant auth is deferred to the existing security gate; this corrects the earlier ambiguous P2+ wording. Never expose it publicly.
Tests require real PostgreSQL via Testcontainers and fail if Docker is unavailable. No H2 and no skip-by-environment. Database migrations and a new adapter instance test persistence; packaged-process restart tests are additional evidence, not a database power-loss drill.
