package com.payledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the database itself rejects bad money data, independent of any Java code. */
class SchemaTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void exactlyOneSystemUserWithWalletIsSeeded() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM users u JOIN wallets w ON w.user_id = u.id WHERE u.role = 'SYSTEM'
                """, Long.class);
        assertThat(count).isEqualTo(1);
        assertRejected(() -> newUser("SYSTEM"), "ux_users_single_system");
    }

    @Test
    void walletsHaveNoBalanceColumn() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'wallets' AND column_name LIKE '%balance%'
                """, Long.class);
        assertThat(count).isZero();
    }

    @Test
    void userConstraints() {
        String email = uniqueEmail();
        jdbc.update("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', 'CUSTOMER')", email);

        assertRejected(() -> jdbc.update("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', 'MERCHANT')", email),
                "uq_users_email");
        assertRejected(() -> jdbc.update("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', 'CUSTOMER')",
                "Upper" + uniqueEmail()), "ck_users_email_lower");
        assertRejected(() -> newUser("ADMIN"), "ck_users_role");
    }

    @Test
    void walletConstraints() {
        long user = newUser("CUSTOMER");
        jdbc.update("INSERT INTO wallets (user_id) VALUES (?)", user);

        assertRejected(() -> jdbc.update("INSERT INTO wallets (user_id) VALUES (?)", user), "uq_wallets_user");
        assertRejected(() -> jdbc.update("INSERT INTO wallets (user_id, currency) VALUES (?, 'USD')", newUser("CUSTOMER")),
                "ck_wallets_currency");
        assertRejected(() -> jdbc.update("INSERT INTO wallets (user_id) VALUES (-1)"), "fk_wallets_user");
    }

    @Test
    void paymentRowConstraints() {
        long a = newWallet("CUSTOMER");
        long b = newWallet("MERCHANT");

        assertRejected(() -> movement(a, b, 0, "PAYMENT", null), "ck_payments_amount_positive");
        assertRejected(() -> movement(a, b, -5, "PAYMENT", null), "ck_payments_amount_positive");
        assertRejected(() -> movement(a, a, 100, "PAYMENT", null), "ck_payments_distinct_wallets");
        assertRejected(() -> movement(a, b, 100, "WITHDRAWAL", null), "ck_payments_type");
        assertRejected(() -> movement(a, b, 100, "REFUND", null), "ck_payments_refund_parent");

        long original = movement(a, b, 100, "PAYMENT", null);
        assertRejected(() -> movement(b, a, 50, "PAYMENT", original), "ck_payments_refund_parent");
        assertThat(movement(b, a, 50, "REFUND", original)).isPositive();
    }

    @Test
    void balancedMovementCommitsAndUnbalancedIsRejectedAtCommit() {
        long a = newWallet("CUSTOMER");
        long b = newWallet("MERCHANT");

        long paymentId = movement(a, b, 250, "PAYMENT", null);
        Long sum = jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entries WHERE payment_id = ?", Long.class, paymentId);
        assertThat(sum).isZero();

        assertRejected(() -> tx.executeWithoutResult(s -> {
            long p = insertPayment(a, b, 300, "PAYMENT", null);
            entry(a, p, -300, "DEBIT");
            entry(b, p, 299, "CREDIT");
        }), "sum to -1, expected 0");
    }

    @Test
    void paymentWithoutEntriesIsRejectedAtCommit() {
        long a = newWallet("CUSTOMER");
        long b = newWallet("MERCHANT");

        assertRejected(() -> tx.executeWithoutResult(s -> insertPayment(a, b, 100, "PAYMENT", null)),
                "has no ledger entries");
    }

    @Test
    void entrySignMustMatchType() {
        long a = newWallet("CUSTOMER");
        long b = newWallet("MERCHANT");

        assertRejected(() -> tx.executeWithoutResult(s -> {
            long p = insertPayment(a, b, 100, "PAYMENT", null);
            entry(a, p, 100, "DEBIT");
            entry(b, p, -100, "CREDIT");
        }), "ck_ledger_sign_matches_type");
        assertRejected(() -> tx.executeWithoutResult(s -> {
            long p = insertPayment(a, b, 100, "PAYMENT", null);
            entry(a, p, -100, "HOLD");
            entry(b, p, 100, "CREDIT");
        }), "ck_ledger_entry_type");
    }

    @Test
    void ledgerAndPaymentsAreAppendOnly() {
        long a = newWallet("CUSTOMER");
        long b = newWallet("MERCHANT");
        long p = movement(a, b, 100, "PAYMENT", null);

        assertRejected(() -> jdbc.update("UPDATE ledger_entries SET amount = amount * 2 WHERE payment_id = ?", p),
                "ledger_entries is append-only: UPDATE rejected");
        assertRejected(() -> jdbc.update("DELETE FROM ledger_entries WHERE payment_id = ?", p),
                "ledger_entries is append-only: DELETE rejected");
        assertRejected(() -> jdbc.execute("TRUNCATE ledger_entries"),
                "ledger_entries is append-only: TRUNCATE rejected");
        assertRejected(() -> jdbc.update("UPDATE payments SET amount = 1 WHERE id = ?", p),
                "payments is append-only: UPDATE rejected");
        assertRejected(() -> jdbc.update("DELETE FROM payments WHERE id = ?", p),
                "payments is append-only: DELETE rejected");
    }

    @Test
    void idempotencyKeyIsUniquePerUser() {
        long u1 = newUser("CUSTOMER");
        long u2 = newUser("CUSTOMER");
        UUID key = UUID.randomUUID();
        String hash = "a".repeat(64);
        String insert = "INSERT INTO idempotency_keys (user_id, key, request_hash, status_code, response_body) VALUES (?, ?, ?, ?, ?)";

        jdbc.update(insert, u1, key, hash, 201, "{}");
        jdbc.update(insert, u2, key, hash, 201, "{}");

        assertRejected(() -> jdbc.update(insert, u1, key, hash, 201, "{}"), "pk_idempotency_keys");
        assertRejected(() -> jdbc.update(insert, u1, UUID.randomUUID(), "not-a-sha256", 201, "{}"),
                "ck_idempotency_hash_hex");
        assertRejected(() -> jdbc.update(insert, u1, UUID.randomUUID(), hash, 201, null), "ck_idempotency_response");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static void assertRejected(Runnable action, String expectedMessagePart) {
        assertThatThrownBy(action::run)
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                        .contains(expectedMessagePart));
    }

    private static String uniqueEmail() {
        return "schema-" + UUID.randomUUID() + "@test.local";
    }

    private long newUser(String role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', ?) RETURNING id",
                Long.class, uniqueEmail(), role);
    }

    private long newWallet(String role) {
        return jdbc.queryForObject("INSERT INTO wallets (user_id) VALUES (?) RETURNING id", Long.class, newUser(role));
    }

    /** A complete, balanced money movement committed in one transaction. Returns the payment id. */
    private long movement(long payer, long payee, long amount, String type, Long parent) {
        return tx.execute(s -> {
            long p = insertPayment(payer, payee, amount, type, parent);
            entry(payer, p, -amount, "DEBIT");
            entry(payee, p, amount, "CREDIT");
            return p;
        });
    }

    private long insertPayment(long payer, long payee, long amount, String type, Long parent) {
        return jdbc.queryForObject("""
                INSERT INTO payments (payer_wallet_id, payee_wallet_id, amount, status, type, parent_payment_id)
                VALUES (?, ?, ?, 'COMPLETED', ?, ?) RETURNING id
                """, Long.class, payer, payee, amount, type, parent);
    }

    private void entry(long wallet, long payment, long amount, String type) {
        jdbc.update("INSERT INTO ledger_entries (wallet_id, payment_id, amount, entry_type) VALUES (?, ?, ?, ?)",
                wallet, payment, amount, type);
    }
}
