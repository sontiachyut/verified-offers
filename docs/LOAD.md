# Bounded local workload observation

Run recorded 2026-09-17 03:10 UTC (September 16 local), commit `6174fec`.
Apple M4, 16GiB host RAM, macOS arm64, Java 21.0.8 with two effective processors
and 512MiB Java heap cap. Single local PostgreSQL/Kafka/OpenSearch instances;
OpenSearch heap 512MiB, Kafka heap 256–512MiB. No parallel heavy job was run.

The fixed synthetic workload uses 250 offers, 10 merchants, seed identifier
20260916, 20 seconds of scheduled arrivals, 10 searches/s plus 2 updates/s, and
at most two requests in flight. Five searches warm up the path. Initial catalog
and projection are bootstrapped offline (excluded from timing); measured updates
go through the actual HTTP → PostgreSQL outbox → Kafka → index pipeline.
Unauthenticated local mode; no identity-provider performance claim.

| Operation | Successful / attempted | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| Search plus explicit cursor close | 200 / 200 | 25.91ms | 38.18ms | 47.65ms |
| Source update HTTP | 40 / 40 | 12.49ms | 20.74ms | 34.18ms |

No driver backpressure drops and no observed request failures. Measured drain
duration was 19.93s (last arrival at 19.9s); successful rates were 10.04/s and
2.01/s. A zero-failure sample does not establish a long-run error SLO. Nearest-rank
percentiles include all attempted request durations. Search measurements include
closing the PIT, so they are not a pure single-GET latency measurement.

Outbox pending count was zero at the end. That is NOT proof of zero index lag;
this run did not measure source-to-search freshness percentiles. The publisher's
one-record-per-250ms polling and single-record indexing remain throughput limits
to measure/tune before the proposed larger workload.

Raw samples and configuration: [p6-load.json](validation/p6-load.json).
This does **not** demonstrate the proposed 100k offers / 100 searches/s /
20 updates/s for 15 minutes, HA, production cost or representative user traffic.
The smaller run was chosen for thermal safety, not to redefine the original gate.

## Reproduce deliberately

```sh
nice -n 10 env JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx512m' \
  ./mvnw -Dtest=ReadinessTest -Dit.test=LocalLoadExperiment verify
```

This explicit experiment is not silently skipped inside the normal test suite:
its class is deliberately not an `*IT` default-suite name. It starts and cleans
up its own real dependencies, writes `target/validation/load.json`, and records
errors/drops rather than retrying them away. A test-process exit of zero means the
experiment completed; inspect the report to judge workload outcomes. Full
`./mvnw verify` remains the separate correctness gate. Do not run stress loops or
increase duration/concurrency on a hot laptop. Representative target runs need a
reviewed environment/resource budget; do not extrapolate these observations.
