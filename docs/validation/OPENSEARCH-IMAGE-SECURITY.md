# OpenSearch image security gate

Scanned 2026-09-15 with Trivy 0.74.0, OS and Java vulnerability scanners,
linux/arm64. Selected image:
`opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509`.

| Candidate | Critical | High | Medium | Low |
|---|---:|---:|---:|---:|
| 3.6.0 (initial candidate, not adopted) | 6 | 269 | 272 | 2 |
| 3.8.0 (selected) | 6 | 81 | 73 | 0 |

Counts are vulnerable package occurrences, not unique CVEs or proof of
exploitability. 3.8.0 reduces findings but does **not** clear the deployment gate.
The six critical occurrences concern CVE-2026-75595 in bundled Netty handler
copies (4.1.133.Final and 4.2.16.Final); the scanner lists 4.1.137.Final or
4.2.17.Final as fixes. Do not replace embedded JARs casually: compatibility and
upstream remediation need review. Re-scan a patched distribution before shared
deployment. See the [sanitized machine-readable report](opensearch-image-scan.json)
for every package, installed/fixed version and advisory URL.

The scan used a local `docker save` archive as read-only input. The initial
remote-image scan stalled and was stopped; its partial output is not evidence.
No Docker socket or private workspace was mounted into the scanner. A temporary
scanner-cache directory held the vulnerability databases; generated archives
and cache were removed after validation. Example reproduction:

```sh
docker pull opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509
docker save -o /tmp/offers-opensearch-image.tar opensearchproject/opensearch:3.8.0
docker run --rm -v /tmp/offers-opensearch-image.tar:/scan/image.tar:ro \
  aquasec/trivy:0.74.0 image --input /scan/image.tar --scanners vuln --timeout 15m --format json
```

This is deliberately a **local-only development exception**, not acceptance for
public use. API and index bind to loopback, only synthetic data is used, the
security plugin is disabled explicitly, and no cloud resources are provisioned.
Local-only binding limits exposure but does not eliminate vulnerabilities.
Re-scan other architectures independently; the arm64 report does not certify the
CI runner's amd64 image. Authentication, TLS, least privilege, patched images and
restore/rebuild exercises remain mandatory deployment gates.

References:
- [OpenSearch 3.8 release](https://github.com/opensearch-project/OpenSearch/releases/tag/3.8.0)
- [Official Docker setup and local security-disabled examples](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/)
