# Kafka development image security

Scan date: 2026-09-14. Scope: linux/arm64 only.

Apache Kafka 4.2.1, manifest digest
`sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0`.
The official Java broker image is pinned in both Compose and integration tests.

Trivy 0.74.0 reported **0 critical, 19 high, 50 medium and 49 low** package
vulnerability entries (118 entries, not necessarily distinct CVEs). No findings
were suppressed. See [the sanitized report](kafka-image-scan.json) for package,
installed/fixed version and advisory references. This report includes OS and
bundled Java dependencies where the scanner identified them; it does not cover
the separately packaged application or CI's linux/amd64 image.

Use only for local synthetic development/testing. Kafka has plaintext listeners
and no authentication in this local configuration. The exposed Compose port and
Kafka integration-test port bind to loopback. The image is not cleared for
public deployment; update/rebuild/re-scan and assess the remaining findings
before considering shared deployment. Functional tests do not establish
security or exploitability of an individual finding.

Reproduce with Trivy 0.74.0 (scanner databases change over time):

```sh
trivy image --image-src remote --scanners vuln --platform linux/arm64 --format json \
  apache/kafka@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0
```

The recorded scan used the `aquasec/trivy:0.74.0` scanner container without
mounting the Docker socket or host files. It downloaded public image metadata
and vulnerability databases directly from registries.
