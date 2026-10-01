# PayLedger progress

Build on JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`, then `./mvnw verify` (Docker must be running).
On restart: read CLAUDE.md and this file, continue from the first unticked phase.

- [x] P0 Scaffold — 2026-10-01 — 1 test
  - Spring Boot 3.5.16, the latest 3.x release; start.spring.io only offers 4.x now, so the pom is hand-written and only the Maven wrapper came from Initializr.
  - The enforcer rule requires JDK [17,18), because `java_home -v 17` silently falls back to a newer JDK when 17 is missing.
  - All tests share one Testcontainers Postgres 16 through `@ServiceConnection` and Spring's context cache. There is no per-test cleanup; tests isolate by creating their own users.
- [ ] P1 Schema (Flyway V2) — CHECKPOINT A
- [ ] P2 Auth
- [ ] P3 Ledger
- [ ] P4 Payments
- [ ] P5 Idempotency + README — CHECKPOINT B
- [ ] P6 Refunds, history, settlement (wait for "continue")
- [ ] P7 Quality + CI
- [ ] P8 Deploy
