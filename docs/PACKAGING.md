# Reproducible local application image

Build the verified jar first with `./mvnw verify`; `Dockerfile` copies only that
artifact into a digest-pinned Temurin 21 JRE. The build context excludes source,
`.git`, `.env`, frontend dependencies and private workspace files. Image UID/GID
10001 is non-root; no shell installer runs and no credentials are baked in.

```sh
docker compose -p verified-offers-app -f compose.app.yaml up --build -d
curl --fail http://127.0.0.1:8081/actuator/health
docker compose -p verified-offers-app -f compose.app.yaml down
```

This optional Compose file is a **volatile local-demo packaging smoke test**, not
the durable PostgreSQL/Kafka/OpenSearch deployment. The container must listen on
its own network interface for port publishing, but the HOST published port is
explicitly loopback. Do not change it to an all-interface host binding. The root
filesystem is read-only, `/tmp` is a 64MiB tmpfs, capabilities are dropped, privilege
escalation disabled, and CPU/memory/PIDs capped. State disappears at shutdown.
Do not run it while another API owns 8081; it does not adopt existing services.

For an isolated automatic smoke test, run `docker build -t verified-offers:local .`
then `node scripts/container-smoke.mjs`. It creates a unique container on a dynamic
loopback port, checks non-root/read-only execution and actual ingest/verify/conflict
HTTP behavior, then removes only that container. No existing 8081 service is touched.

The durable adapters currently require explicit host-loopback OpenSearch endpoints;
do not pretend this demo Compose file is a container-network production stack.
TLS, secret injection, identity provider, network policies, runtime role setup,
image remediation and durable deployment topology remain separate gates.

The base was resolved from the official registry on 2026-09-16:
Temurin 21.0.12+8 JRE Jammy, manifest digest
`sha256:bce52ea7da1f72e6bf5bec505e63b6eb55ba79ad1226903579f77eab1a80139a`.
Pinned does not mean vulnerability-free. Scan the finished application image
(including bundled Java dependencies) and every deployment architecture before
release. See the validation security records; do not suppress findings to pass.
