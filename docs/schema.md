# PayLedger database schema

Source of truth: `src/main/resources/db/migration/V2__core_schema.sql` (V1 is an empty baseline).
Applied migrations are never edited; changes go in a new `V<n>__name.sql`.

## Ground rules the schema enforces

| Rule | How the database enforces it |
|---|---|
| Money is integer paise | Every amount is `BIGINT`. No `NUMERIC`, `REAL` or `DOUBLE` column exists. |
| There is no stored balance | `wallets` has no balance column, and `SchemaTest.walletsHaveNoBalanceColumn` checks this. Balance = `SUM(ledger_entries.amount)`. |
| Every movement is double-entry | A deferred constraint trigger rejects the COMMIT if a payment's entries don't sum to 0. |
| Every payment moves money | A deferred constraint trigger rejects a payment that has no ledger entries. |
| History is immutable | Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on `ledger_entries` and `payments`. |

The application checks the same things first (`LedgerService` throws before writing). The database is the second line of defence, so a bug, a manual SQL session or a future service can't commit broken money.

---

## `users`
A person or business that can log in, plus one internal SYSTEM account.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT IDENTITY` PK | `GENERATED ALWAYS`, so callers can't pick their own ids. |
| `email` | `VARCHAR(254)` | 254 is the longest valid email address. |
| `password_hash` | `VARCHAR(100)` | A BCrypt hash, which is 60 chars. |
| `role` | `VARCHAR(16)` | `CUSTOMER`, `MERCHANT` or `SYSTEM`. |
| `created_at` | `TIMESTAMPTZ` | Stored in UTC. |

| Constraint | Why |
|---|---|
| `uq_users_email` UNIQUE(email) | One account per email. The API maps a violation to 409, even when two sign-ups race. |
| `ck_users_email_lower` CHECK(email = lower(email)) | Stops `A@x.com` and `a@x.com` becoming two accounts. The app lowercases before insert, and the DB makes sure that actually happened. |
| `ck_users_role` | Only known roles can exist. |
| `ux_users_single_system` partial unique index on `role WHERE role='SYSTEM'` | There is exactly one SYSTEM user, so "the system wallet" always means one row. |

**Seed row:** `system@payledger.internal`, role SYSTEM, password hash `!`. `!` isn't a valid BCrypt hash, so no password can ever match it.

## `wallets`
One INR wallet per user.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT IDENTITY` PK | This is the id the API exposes (`merchantWalletId`). It is also the lock order: lower id is locked first. |
| `user_id` | `BIGINT` FK → users | |
| `currency` | `VARCHAR(3)` | Default `INR`. |

| Constraint | Why |
|---|---|
| `uq_wallets_user` UNIQUE(user_id) | One wallet per user. Registration creates the user and the wallet in one transaction. |
| `fk_wallets_user` | No orphan wallets. |
| `ck_wallets_currency` CHECK(currency='INR') | The product is single-currency. This stops cross-currency sums from silently mixing units. Supporting more currencies would take a migration and FX rules, not just a new value. |

The SYSTEM user's wallet is seeded too. Top-ups debit it, so its balance is always minus the total amount ever loaded.

**There is no `balance` column, on purpose.** A stored balance is a second copy of the truth, and it can drift: a lost update or a missed code path is enough. Deriving the balance from the ledger means it can never disagree with the history.

## `payments`
One row per business event: a customer paying a merchant, a refund, or a top-up.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT IDENTITY` PK | |
| `payer_wallet_id` | FK → wallets | Money leaves this wallet. For a TOPUP it is the SYSTEM wallet. |
| `payee_wallet_id` | FK → wallets | Money arrives in this wallet. |
| `amount` | `BIGINT` | Paise. Always positive; the direction comes from payer and payee. |
| `status` | `VARCHAR(16)` | Only `COMPLETED`. A failed attempt (such as insufficient funds) rolls back and writes no row at all, so there are no half-states to reconcile. |
| `type` | `VARCHAR(16)` | `PAYMENT`, `REFUND` or `TOPUP`. |
| `parent_payment_id` | FK → payments, nullable | For a REFUND, the payment it reverses. |
| `note` | `VARCHAR(140)` | Free text from the payer. |
| `created_at` | `TIMESTAMPTZ` | |

| Constraint | Why |
|---|---|
| `ck_payments_amount_positive` amount > 0 | A zero or negative payment is meaningless and could be used to reverse money flow. |
| `ck_payments_distinct_wallets` payer ≠ payee | No self-payments, which would only inflate volume. The API also returns 400 for this. |
| `ck_payments_refund_parent` (type='REFUND') = (parent IS NOT NULL) | Every refund points at its original payment, and nothing else does. |
| `ck_payments_status`, `ck_payments_type` | Closed sets of values. |
| `fk_payments_*` | Referential integrity. |
| `trg_payments_append_only`, `trg_payments_no_truncate` | Payments are facts. A refund is a new row; the original is never edited. |
| `trg_payments_have_entries` (deferred) | A payment can't commit unless it has ledger entries. |

| Index | Serves |
|---|---|
| `ix_payments_payer_history (payer_wallet_id, created_at DESC, id DESC)` | Payment history, newest first, for the paying side. `id` breaks ties so pagination is stable. |
| `ix_payments_payee_history (payee_wallet_id, created_at DESC, id DESC)` | The same history for the receiving side. History is the union of both. |
| `ix_payments_parent (parent_payment_id) WHERE NOT NULL` | "How much has been refunded so far", without indexing the many NULLs. |

## `ledger_entries`
The double-entry ledger, and the only source of balances.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT IDENTITY` PK | |
| `wallet_id` | FK → wallets | The wallet this entry affects. |
| `payment_id` | FK → payments | The event that caused it. |
| `amount` | `BIGINT` | Signed paise: negative = DEBIT (money out), positive = CREDIT (money in). |
| `entry_type` | `VARCHAR(8)` | `DEBIT` or `CREDIT`. |
| `created_at` | `TIMESTAMPTZ` | |

A payment of 150 from wallet 7 to wallet 9 writes:

| wallet_id | payment_id | amount | entry_type |
|---|---|---|---|
| 7 | 42 | -150 | DEBIT |
| 9 | 42 | +150 | CREDIT |

| Constraint | Why |
|---|---|
| `ck_ledger_sign_matches_type` | DEBIT must be < 0 and CREDIT must be > 0. This also rules out zero, and makes the sign and the label impossible to contradict. |
| `ck_ledger_entry_type` | A closed set. |
| `fk_ledger_wallet`, `fk_ledger_payment` | Every entry belongs to a real wallet and a real payment. |
| `trg_ledger_entries_balanced`: constraint trigger, `DEFERRABLE INITIALLY DEFERRED` | At COMMIT, `SUM(amount)` for each touched payment must be 0. It's deferred so a transaction can insert the debit and credit one at a time. |
| `trg_ledger_entries_append_only`, `trg_ledger_entries_no_truncate` | Entries are never changed or removed. Mistakes are fixed with new reversing entries, as in an accountant's ledger. |

| Index | Serves |
|---|---|
| `ix_ledger_wallet (wallet_id, created_at) INCLUDE (amount)` | `SELECT SUM(amount) WHERE wallet_id = ?`, the balance. INCLUDE lets Postgres answer it from the index alone. `created_at` serves per-day settlement. |
| `ix_ledger_payment (payment_id)` | The balanced-commit trigger, and looking up a payment's entries. |

Invariant: because every payment nets to zero, `SELECT SUM(amount) FROM ledger_entries` is always exactly 0 across the whole database. The tests check this after the concurrent runs.

## `idempotency_keys`
Remembers the response to each client-supplied `Idempotency-Key`, so a retried POST can't move money twice.

| Column | Type | Notes |
|---|---|---|
| `user_id` | FK → users | Keys are scoped per user, so two users can pick the same UUID. |
| `key` | `UUID` | The client's `Idempotency-Key` header. |
| `request_hash` | `VARCHAR(64)` | Hex SHA-256 of method + path + canonical JSON body. |
| `status_code` | `INT`, nullable | The HTTP status of the first response. |
| `response_body` | `TEXT`, nullable | The JSON body of the first response. |
| `created_at` | `TIMESTAMPTZ` | Useful for a future expiry job. |

| Constraint | Why |
|---|---|
| `pk_idempotency_keys` PRIMARY KEY(user_id, key) | The UNIQUE(user_id, key) rule. Postgres blocks a second insert of the same key until the first transaction finishes. That is what makes 10 concurrent retries produce one payment. |
| `ck_idempotency_hash_hex` | The hash really is 64 lowercase hex chars. |
| `ck_idempotency_status` | Only a valid HTTP status code. |
| `ck_idempotency_response` | `status_code` and `response_body` are either both set or both NULL. |

The key row is inserted at the start of the payment transaction, with status and body NULL, then filled in before COMMIT. Other transactions therefore only ever see completed rows. If the payment fails, the rollback removes the key too, so the client can retry.
