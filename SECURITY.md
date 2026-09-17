# Security

Local-development reference implementation. Not approved for internet exposure
or real data. Profiles are unauthenticated by default; an opt-in signed-token
mode is documented in [AUTHENTICATION](docs/AUTHENTICATION.md). Bind loopback and
use synthetic inputs only. Read the [threat model](docs/THREAT-MODEL.md), image
findings and [database role boundary](docs/DATABASE-ROLES.md) before shared use.

Do not put credentials or private data in public issues. For a vulnerability, use GitHub private vulnerability reporting if enabled; otherwise contact the repository owner privately. Do not publish exploit details or sensitive logs in an issue.

Production deployment is gated on the security and operational checks in docs/SPEC.md.
