# Separate schema ownership from runtime access

`postgres-local` defaults to the simple development credential for compatibility.
It is not least privilege by default. To exercise the hardened database boundary,
migrate a dedicated synthetic database with its owner, then execute
`ops/database-roles.sql` as that owner. The script creates NOLOGIN group roles;
it does not embed passwords, provision cloud accounts or change other databases.
It revokes public schema creation in this dedicated database. Review before use
on any existing shared database; do not run it against unrelated databases.

Provision a separate LOGIN through your secret-management process and grant
`offers_runtime` to the API/worker login. Supply that login through the existing
APP_DATABASE_* environment variables and start with `--spring.flyway.enabled=false`.
Run migrations separately as schema owner before starting runtime. Never give the
runtime owner/superuser/CREATEROLE privileges or leave migration credentials in
the API process. No password is included in the repository or example commands.

The runtime can ingest, process feeds and publish/index, but cannot delete or
truncate tables, alter schema, rewrite immutable source versions or read migration
metadata. `offers_operator` additionally permits local rebuild/reconciliation
state transitions; use a distinct operator login, not the runtime service login.
The script grants no default privileges on future tables: every migration needs
an explicit permission review and updated role tests.

Rerunning setup reconciles table/sequence privileges of these two application
groups in the dedicated public schema, removing accidental grants before applying
the allowlist. It refuses to adopt pre-existing privileged or LOGIN group roles.
This is another reason not to run the script in an unrelated/shared database.

This is database capability separation, not PostgreSQL row-level tenant security.
The trusted application credential reads multiple tenants; authenticated HTTP
authorization is the tenant boundary. A compromised application login remains a
cross-tenant read/write risk within its granted capabilities. Independent service
roles, RLS and audited secrets rotation are deployment decisions, not implied here.

`DatabaseRolesIT` uses a real non-owner LOGIN and checks successful ingestion/replay
and outbox acknowledgement, then rejects history edits, deletes, truncation, DDL,
migration metadata access and role escalation. It also executes setup twice.
