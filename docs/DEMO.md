# Local reference demonstration

Prerequisites: Java 21, Node.js 22+ and make. Maven is downloaded by the checked-in wrapper. No Docker required for P1.

Run `make demo` from the repository root. It executes the verification suite, packages the application, launches a fresh JVM on a dynamic loopback port, exercises HTTP assertions and terminates only that child process. It does not write persistent data or call external merchants/providers.

For manual exploration: `make run` starts the local-demo profile on port 8081. State is lost at shutdown. API details and field constraints are in SPEC.md.

The demonstration ingests a source offer, verifies its price, changes the authoritative price, rejects the old claim and an old version replay, then applies a tombstone. It does not yet demonstrate an asynchronous search index, merchant feed jobs or model extraction.

This is synthetic evidence of the current reference behavior, not a performance benchmark or deployment claim.
