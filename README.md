# PayLedger

**A banking-style payments and wallet REST API that stays correct when 100 requests hit the same wallet at once.**

Customers top up an INR wallet and pay merchants. Every movement is recorded in an append-only, double-entry ledger, and a balance is always computed from that ledger, never stored. The project is about correctness under concurrency, not feature count.

Java 17 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) · Testcontainers

## What it does

- **Auth:** register as `CUSTOMER` or `MERCHANT`, log in, get a 1-hour HS256 JWT. Passwords hashed with BCrypt.
- **Wallets:** one INR wallet per user. Top-ups are funded by an internal SYSTEM wallet.
- **Payments:** a user pays a merchant wallet. Insufficient funds returns `422` and writes nothing.
- **Idempotency:** every payment carries an `Idempotency-Key`; retries replay the original response.

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/api/auth/register` | public | `{email, password, role}` → `201` |
| `POST` | `/api/auth/login` | public | `{email, password}` → `{accessToken, tokenType, expiresIn}` |
| `POST` | `/api/wallets/topup` | Bearer | `{amount}` in paise → `201` with the new balance |
| `GET` | `/api/wallets/me` | Bearer | `{walletId, currency, balance}` |
| `POST` | `/api/payments` | Bearer + `Idempotency-Key` | `{merchantWalletId, amount, note?}` → `201` |
| `GET` | `/actuator/health` | public | `{"status":"UP"}` |

Errors share one shape, `{code, message, details}`, e.g. `422 INSUFFICIENT_FUNDS` with `{"balance":100,"amount":150}` (`error/GlobalExceptionHandler.java`).

## Ledger design

Paths below are under `src/main/java/com/payledger/` and `src/main/resources/db/migration/`. See also [docs/schema.md](docs/schema.md).

- **Money is `BIGINT` paise.** No floats anywhere in the schema or API.
- **No balance column.** Balance is `SUM(ledger_entries.amount)` for the wallet (`repository/LedgerEntryRepository.java`), served by an index-only scan on `ix_ledger_wallet (wallet_id, created_at) INCLUDE (amount)` (`V2__core_schema.sql:66`). A derived balance cannot drift from its own history.
- **Append-only.** Row triggers reject `UPDATE`/`DELETE` and statement triggers reject `TRUNCATE` on both `ledger_entries` and `payments` (`V2__core_schema.sql:84-102`). A correction must be a new row.
- **Every payment sums to zero.** `service/LedgerService.java` refuses unbalanced or zero-amount postings, and Postgres backs it up with `DEFERRABLE INITIALLY DEFERRED` constraint triggers that reject, at COMMIT, any payment whose entries don't sum to 0 or that has no entries (`V2__core_schema.sql:104-138`). Even a raw SQL session cannot commit broken money.
- **Ledger writes can't run on their own.** `LedgerService` is `@Transactional(propagation = MANDATORY)`, so it always runs inside the caller's transaction and locks.

## The double-spend race and the fix

A payment is check-then-act: read the balance, debit if it is high enough. Without a lock, concurrent payments read the same balance, all pass the check, and the wallet overdraws.

**Fix** (`service/PaymentService.java`):
1. Lock both wallets with `SELECT * FROM wallets WHERE id = :id FOR UPDATE` (`repository/WalletRepository.java:25`).
2. Always lock the **lower wallet id first** (`lockInOrder`, lines 76-79). If A→B and B→A each locked the payer first, each could hold one row and wait for the other: a deadlock. One global order makes that cycle impossible.
3. Read the balance only **after** the locks are held (line 62). Under READ COMMITTED that read sees every debit committed while this request waited.

**Proof** (`src/test/java/com/payledger/PaymentTest.java`):

| Test | Scenario | Asserts |
|---|---|---|
| `concurrentPaymentsNeverOverdraw` | Top up 10,000 paise; 100 threads released by one latch each pay 150 paise over HTTP | exactly **66 × 201 and 34 × 422**, final balance 100, merchant 9,900, ledger sum 0 |
| `withoutTheRowLockConcurrentPaymentsOverdraw` | Same 100 × 150 against 10,000, but the same check-then-debit runs **without** `FOR UPDATE` (a test-only transaction calling `LedgerService`; the app has no way to skip the lock) | **more than 66 successes and a negative balance**, while the ledger still sums to 0 |
| `merchantsPayingEachOtherConcurrentlyDoNotDeadlock` | 50 A→B and 50 B→A at once | all 100 return 201, balances unchanged |

Recorded results of the unlocked test in three full-suite runs on a local machine: **70 successes / −500 paise, 70 / −500, 74 / −1,100**. The number varies because it is a race; the test only requires an overdraft, and it reproduced on every run with no artificial delay. The DB triggers did not stop it: double entry keeps the books balanced, but only the row lock prevents overdraft.

## Idempotency

`service/IdempotencyService.java` runs inside the payment transaction and first claims the key with `INSERT ... ON CONFLICT (user_id, key) DO NOTHING` against the primary key `(user_id, key)` (`V2__core_schema.sql:69-81`).

- A concurrent duplicate blocks on the primary key until the first request finishes, then replays the stored response with `Idempotent-Replayed: true`.
- Same key with a different body (SHA-256 of the request) → `409 IDEMPOTENCY_KEY_REUSED`.
- A failed attempt (e.g. `422`) rolls back with the payment, so the key is not burned.
- `IdempotencyTest.tenConcurrentRequestsWithSameKeyCreateExactlyOnePayment`: **10 concurrent requests with one key → 1 payment**; all 10 get the same 201 body.

## Run it

**Prerequisites:** JDK 17 (the Maven enforcer rule in `pom.xml` requires `[17,18)`) and Docker.

**Tests** (Testcontainers starts its own `postgres:16-alpine`; no H2, no DB mocks):

```bash
./mvnw test
```

42 integration tests across `AuthTest`, `HealthTest`, `IdempotencyTest`, `LedgerTest`, `PaymentTest` and `SchemaTest`.

**App:**

```bash
docker compose up -d          # local PostgreSQL 16 on port 5432 (set POSTGRES_PORT to change)
export JWT_SECRET=...         # required, at least 32 bytes, e.g. from: openssl rand -base64 48
./mvnw spring-boot:run        # http://localhost:8080
```

Flyway creates the schema on startup. The app refuses to start without `JWT_SECRET`; there is no default. Optional: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (the defaults in `application.yml` match the local-only `docker-compose.yml`).

Quick try:

```bash
curl -s localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"shop@example.in","password":"merchant123","role":"MERCHANT"}'   # note the walletId
curl -s localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.in","password":"customer123","role":"CUSTOMER"}'
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.in","password":"customer123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s localhost:8080/api/wallets/topup -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"amount":50000}'
curl -s localhost:8080/api/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"merchantWalletId":<walletId>,"amount":12000}'
```

## Limitations / not done

Per [PROGRESS.md](PROGRESS.md), phases P6–P8 are not started:

- **No refunds, payment history or settlement.** The schema reserves a `REFUND` type and a refund-parent check, but there is no refund code.
- **No CI** pipeline and **no deployment**.
- Merchants are a user role, not a separate resource; there is no merchant management API.
- The concurrency tests show correct behaviour for 100 concurrent requests in a test run; they are not a formal proof or a load test.
