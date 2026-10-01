# PayLedger progress

Build on JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`, then `./mvnw verify` (Docker must be running).
On restart: read CLAUDE.md and this file, continue from the first unticked phase.

- [x] P0 Scaffold — 2026-10-01 — 1 test
  - Spring Boot 3.5.16, the latest 3.x release; start.spring.io only offers 4.x now, so the pom is hand-written and only the Maven wrapper came from Initializr.
  - The enforcer rule requires JDK [17,18), because `java_home -v 17` silently falls back to a newer JDK when 17 is missing.
  - All tests share one Testcontainers Postgres 16 through `@ServiceConnection` and Spring's context cache. There is no per-test cleanup; tests isolate by creating their own users.
- [x] P1 Schema (Flyway V2) — CHECKPOINT A — 2026-10-01 — 11 tests
  - The DB is the second line of defence. Deferred constraint triggers reject, at COMMIT, any payment whose entries don't sum to 0 and any payment with no entries. Triggers also make `ledger_entries` and `payments` append-only, rejecting UPDATE, DELETE and TRUNCATE.
  - The SYSTEM wallet is a seeded SYSTEM-role user, kept unique by a partial unique index. Its password hash is `!`, so it can never log in. Wallets keep a single NOT NULL user FK.
  - `payments.status` only allows COMPLETED, because a failed attempt rolls back and writes nothing. `idempotency_keys` uses PK(user_id, key) as its unique constraint.
  - Gotcha: `SYSTEM_USER` is a reserved SQL function in PG16 and can't be used as a CTE name.
- [x] P2 Auth — 2026-10-01 — 20 tests
  - JWT uses Spring Security's resource server with NimbusJwtEncoder/Decoder (HS256), so there's no hand-written filter. JWT_SECRET has no default, and the app fails at startup if it is missing or under 32 bytes.
  - Duplicate emails are caught by the DB unique constraint and mapped to 409, not by a check-then-insert. A test fires 10 parallel sign-ups and gets exactly one 201. Emails are lowercased.
  - Unknown email and wrong password give the same 401 body, and both run BCrypt (unknown emails are checked against a dummy hash), so neither the response nor its timing reveals whether an account exists. Passwords are also capped at 72 bytes, BCrypt's input limit.
- [ ] P3 Ledger
- [ ] P4 Payments
- [ ] P5 Idempotency + README — CHECKPOINT B
- [ ] P6 Refunds, history, settlement (wait for "continue")
- [ ] P7 Quality + CI
- [ ] P8 Deploy
