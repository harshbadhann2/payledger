-- PayLedger core schema. Amounts are BIGINT paise. There is no balance column anywhere:
-- a wallet's balance is SUM(ledger_entries.amount). See docs/schema.md.

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email       UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_role        CHECK (role IN ('CUSTOMER', 'MERCHANT', 'SYSTEM'))
);
-- At most one SYSTEM user: it owns the wallet that funds top-ups.
CREATE UNIQUE INDEX ux_users_single_system ON users (role) WHERE role = 'SYSTEM';

CREATE TABLE wallets (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    currency   VARCHAR(3)  NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user     UNIQUE (user_id),
    CONSTRAINT fk_wallets_user     FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_wallets_currency CHECK (currency = 'INR')
);

CREATE TABLE payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payer_wallet_id   BIGINT      NOT NULL,
    payee_wallet_id   BIGINT      NOT NULL,
    amount            BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    type              VARCHAR(16) NOT NULL,
    parent_payment_id BIGINT,
    note              VARCHAR(140),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_payments_payer           FOREIGN KEY (payer_wallet_id)   REFERENCES wallets (id),
    CONSTRAINT fk_payments_payee           FOREIGN KEY (payee_wallet_id)   REFERENCES wallets (id),
    CONSTRAINT fk_payments_parent          FOREIGN KEY (parent_payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_status          CHECK (status IN ('COMPLETED')),
    CONSTRAINT ck_payments_type            CHECK (type IN ('PAYMENT', 'REFUND', 'TOPUP')),
    CONSTRAINT ck_payments_distinct_wallets CHECK (payer_wallet_id <> payee_wallet_id),
    CONSTRAINT ck_payments_refund_parent   CHECK ((type = 'REFUND') = (parent_payment_id IS NOT NULL))
);
-- History: "my payments, newest first" from either side of the payment.
CREATE INDEX ix_payments_payer_history ON payments (payer_wallet_id, created_at DESC, id DESC);
CREATE INDEX ix_payments_payee_history ON payments (payee_wallet_id, created_at DESC, id DESC);
-- Refunds: "how much of payment X is already refunded".
CREATE INDEX ix_payments_parent ON payments (parent_payment_id) WHERE parent_payment_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wallet_id  BIGINT      NOT NULL,
    payment_id BIGINT      NOT NULL,
    amount     BIGINT      NOT NULL,
    entry_type VARCHAR(8)  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ledger_wallet          FOREIGN KEY (wallet_id)  REFERENCES wallets (id),
    CONSTRAINT fk_ledger_payment         FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_ledger_entry_type      CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_sign_matches_type CHECK ((entry_type = 'DEBIT' AND amount < 0)
                                               OR (entry_type = 'CREDIT' AND amount > 0))
);
-- Balance = SUM(amount) WHERE wallet_id = ?; INCLUDE (amount) makes it an index-only scan.
CREATE INDEX ix_ledger_wallet ON ledger_entries (wallet_id, created_at) INCLUDE (amount);
CREATE INDEX ix_ledger_payment ON ledger_entries (payment_id);

CREATE TABLE idempotency_keys (
    user_id       BIGINT      NOT NULL,
    key           UUID        NOT NULL,
    request_hash  VARCHAR(64) NOT NULL,
    status_code   INT,
    response_body TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys     PRIMARY KEY (user_id, key),
    CONSTRAINT fk_idempotency_user     FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_idempotency_hash_hex CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_status   CHECK (status_code BETWEEN 100 AND 599),
    CONSTRAINT ck_idempotency_response CHECK ((status_code IS NULL) = (response_body IS NULL))
);

-- Append-only: ledger entries and payments are facts. Corrections are new rows (refunds).
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % rejected', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_payments_append_only
    BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_payments_no_truncate
    BEFORE TRUNCATE ON payments
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

-- Double entry, checked at COMMIT (deferred) so a transaction can insert the entries one by one.
-- Backstop for LedgerService: even a raw SQL insert cannot commit unbalanced money.
CREATE FUNCTION check_payment_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total FROM ledger_entries WHERE payment_id = NEW.payment_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'ledger entries for payment % sum to %, expected 0', NEW.payment_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_payment_balanced();

-- Every payment must move money: a payment row without ledger entries cannot commit.
CREATE FUNCTION check_payment_has_entries() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger_entries WHERE payment_id = NEW.id) THEN
        RAISE EXCEPTION 'payment % has no ledger entries', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_payments_have_entries
    AFTER INSERT ON payments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_payment_has_entries();

-- The SYSTEM user and wallet. Top-ups debit this wallet, so its balance is minus the total ever
-- loaded into the platform. Password '!' is not a BCrypt hash, so it can never log in.
WITH sys AS (
    INSERT INTO users (email, password_hash, role)
    VALUES ('system@payledger.internal', '!', 'SYSTEM')
    RETURNING id
)
INSERT INTO wallets (user_id) SELECT id FROM sys;
