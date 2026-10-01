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
- [x] P3 Ledger — 2026-10-01 — 26 tests
  - `LedgerService` is `@Transactional(MANDATORY)`, so it fails rather than silently opening its own transaction. It validates sum = 0, rejects zero-amount legs and requires at least 2 legs, all before writing anything. `Math.addExact` guards the sum against overflow.
  - `Payment` and `LedgerEntry` are `@Immutable` entities, so Hibernate never issues an UPDATE. Balance comes only from `LedgerEntryRepository.balanceOf`, a SQL `SUM`.
  - Top-up takes no locks (see the NOTE comment in `WalletService`): a credit can't overdraw anyone, and locking SYSTEM would serialise every top-up. A test runs 60 random top-ups on 8 threads and checks every balance and that the global ledger sum is 0.
- [x] P4 Payments — 2026-10-01 — 35 tests
  - The lock is a native `SELECT * FROM wallets WHERE id = ? FOR UPDATE`, taken on the lower wallet id first and then the higher. The balance is read *after* the locks are held, so under READ COMMITTED the SUM sees every debit that committed while we waited.
  - Payee checks come before locking: self-pay → 400 SELF_PAYMENT, non-merchant → 400 INVALID_PAYEE, unknown wallet → 404. Invalid requests never take a lock.
  - Payers can be any role, including a merchant paying a merchant, which is how the deadlock test pays in both directions.

  **Race condition demo (lock removed, then restored; never committed)**
  - **Without `FOR UPDATE`**, `concurrentPaymentsNeverOverdraw` failed with `expected: 66L but was: 69L`. That's 69 × 150 = 10,350 paise spent from a 10,000-paise wallet, a final balance of **−350**.
  - In plain words: several requests read the balance at the same moment, each saw "10,000, enough for 150", and each wrote its debit. None of them saw the others' debits, because those weren't committed yet when the balance was read. This is a check-then-act race, or lost update.
  - With the lock, the second request blocks on the wallet row until the first commits, then re-reads the balance including the first debit. The check and the write become one indivisible step per wallet. Result: exactly 66 succeed and 34 get 422, the balance ends at 100, and the global ledger sum is 0.
  - **Lock ordering demo:** locking payer-first instead of lower-id-first made `merchantsPayingEachOtherConcurrentlyDoNotDeadlock` fail. Postgres logged `ERROR: deadlock detected` because A→B held A and waited for B while B→A held B and waited for A. Ascending id order means everyone waits in the same direction, so no cycle can form.
- [x] P5 Idempotency + README — CHECKPOINT B — 2026-10-01 — 41 tests
  - The key is claimed *first*, with `INSERT … ON CONFLICT DO NOTHING`, in the same transaction as the payment. A concurrent duplicate blocks on the primary key until the first request commits, then replays its stored response; if the first rolled back, the duplicate runs normally. Ten parallel requests with one key produce exactly one payment.
  - The hash is SHA-256 of `endpoint + canonical JSON of the validated request record`, so whitespace and field order don't matter but any value change → 409. The response is stored as a JSON string and replayed byte for byte, with header `Idempotent-Replayed: true`.
  - README: every curl example was run against the live app (jar + compose Postgres) and the output pasted. `docker-compose.yml` now accepts `POSTGRES_PORT`, because this machine already runs a native Postgres on 5432.
  - Failures (4xx) are not stored: they roll back with the payment, so the key stays free and a retry after a top-up succeeds. `IdempotencyService.execute` is generic and will be reused by refunds in P6.
- [ ] P6 Refunds, history, settlement (wait for "continue")
- [ ] P7 Quality + CI
- [ ] P8 Deploy
