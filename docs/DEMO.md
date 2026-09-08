# Local reference demonstration

Prerequisites: Java 21, Node.js 22+, make and a running Docker daemon. Maven is downloaded by the checked-in wrapper. Since P2, the full verification gate requires Docker for real PostgreSQL tests.

Run `make demo` from the repository root. It runs unit tests, packages the application and executes PostgreSQL/process integration tests using isolated disposable databases. It then launches a fresh in-memory JVM on a dynamic loopback port, exercises HTTP assertions and terminates only that child process. No Compose data or external merchants/providers are touched.

For persisted state and restart exploration, use [the PostgreSQL profile](POSTGRES.md). `make postgres-demo` runs the full gate and prints the database test summaries.

For manual exploration: `make run` starts the local-demo profile on port 8081. State is lost at shutdown. API details and field constraints are in SPEC.md.

The demonstration ingests a source offer, verifies its price, changes the authoritative price, rejects the old claim and an old version replay, then applies a tombstone. It does not yet demonstrate an asynchronous search index, merchant feed jobs or model extraction.

This is synthetic evidence of the current reference behavior, not a performance benchmark or deployment claim.
