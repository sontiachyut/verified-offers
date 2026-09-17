# Finished application image security

The scanner examines the finished JRE + packaged application image, including
bundled Java dependencies. This is separate from the existing PostgreSQL, Kafka
and OpenSearch image records. Local linux/arm64 only; no amd64 certification.

## Initial observation and remediation

Trivy 0.74.0 on 2026-09-17 03:18 UTC reported 3 critical, 53 medium and 16 low
package findings; zero high. The [original sanitized report](application-image-scan-before.json)
records the exact image ID and every unsuppressed finding. The three critical
scanner findings all concern embedded Tomcat 11.0.24:
CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525. Counts are package occurrences,
not an exploitability assessment. Apache's severity ratings differ from the
scanner's; this record does not imply all affected configurations are enabled.

The application overrides Spring Boot 4.1.1's Tomcat BOM version with **11.0.26**.
The 11.0.25 security fixes are included in that later patch release. The official
[security advisory](https://tomcat.apache.org/security-11) and
[11.0.26 changelog](https://tomcat.apache.org/tomcat-11.0-doc/changelog.html) were
checked before updating. Real HTTP/JWT tests run against 11.0.26; final complete
regression, packaged-container smoke and post-patch image scan remain required.

## Reproduce without exposing the Docker socket

```sh
./mvnw verify
docker build --tag verified-offers:local .
node scripts/scan-image.mjs
```

The script exports only the named application image to a newly created temporary
directory, mounts that directory read-only into a digest-pinned Trivy container,
caps the scanner at one CPU/1GiB, and removes only its own scanner/container/temp
files. It never mounts the Docker socket or private workspace. Output is sanitized
to package/version/advisory facts under `target/validation/`; no suppressions.
Scanner databases change over time. A new scan is needed after dependency/base
changes and for every deployment architecture. This script reports findings; its
successful exit does not mean the image is approved for deployment.

Other dependency images still have documented deployment-blocking findings.
No production/public approval follows from patching this one application image.
