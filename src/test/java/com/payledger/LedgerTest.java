package com.payledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payledger.domain.Payment;
import com.payledger.domain.PaymentType;
import com.payledger.service.LedgerService;
import com.payledger.service.LedgerService.Posting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerService ledger;

    @Test
    void newWalletHasZeroBalance() {
        Account account = register("CUSTOMER");

        var response = get("/api/wallets/me", account.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("currency", "INR")
                .containsEntry("balance", 0);
        assertThat(asLong(response.getBody().get("walletId"))).isEqualTo(account.walletId());
    }

    @Test
    void topUpDebitsSystemAndCreditsUser() {
        Account account = register("CUSTOMER");

        var response = post("/api/wallets/topup", Map.of("amount", 50_000), account.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(asLong(response.getBody().get("balance"))).isEqualTo(50_000);
        long paymentId = asLong(response.getBody().get("paymentId"));

        Long systemWallet = jdbc.queryForObject(
                "SELECT w.id FROM wallets w JOIN users u ON u.id = w.user_id WHERE u.role = 'SYSTEM'", Long.class);
        var entries = jdbc.queryForList(
                "SELECT wallet_id, amount, entry_type FROM ledger_entries WHERE payment_id = ? ORDER BY amount", paymentId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("wallet_id", systemWallet).containsEntry("amount", -50_000L)
                .containsEntry("entry_type", "DEBIT");
        assertThat(entries.get(1)).containsEntry("wallet_id", account.walletId()).containsEntry("amount", 50_000L)
                .containsEntry("entry_type", "CREDIT");
        assertThat(jdbc.queryForObject("SELECT type FROM payments WHERE id = ?", String.class, paymentId)).isEqualTo("TOPUP");
        assertThat(balance(account)).isEqualTo(50_000);
    }

    @Test
    void afterRandomConcurrentTopUpsEveryBalanceMatchesAndLedgerSumsToZero() throws Exception {
        List<Account> accounts = List.of(register("CUSTOMER"), register("CUSTOMER"), register("MERCHANT"));
        long[] expected = new long[accounts.size()];
        Random random = new Random(42);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int who = random.nextInt(accounts.size());
            long amount = 1 + random.nextInt(1_000_000);
            expected[who] += amount;
            futures.add(pool.submit(() -> topUp(accounts.get(who), amount)));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        for (int i = 0; i < accounts.size(); i++) {
            assertThat(balance(accounts.get(i))).isEqualTo(expected[i]);
        }
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT payment_id FROM ledger_entries GROUP BY payment_id HAVING SUM(amount) <> 0) x
                """, Long.class)).isZero();
    }

    @Test
    void invalidTopUpAmountsAre400AndWriteNothing() {
        Account account = register("CUSTOMER");

        for (Object amount : List.of(0, -100, 1_000_000_001L, "abc")) {
            var response = post("/api/wallets/topup", Map.of("amount", amount), account.token());
            assertThat(response.getStatusCode()).as("amount %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(post("/api/wallets/topup", Map.of(), account.token()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(account)).isZero();
    }

    @Test
    void unbalancedPostingThrowsAndWritesNothing() {
        Account a = register("CUSTOMER");
        Account b = register("MERCHANT");
        long paymentsBefore = paymentsTouching(a.walletId());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(
                new Payment(a.walletId(), b.walletId(), 100, PaymentType.PAYMENT, null, null),
                List.of(new Posting(a.walletId(), -100), new Posting(b.walletId(), 99)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must sum to zero, got -1");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(
                new Payment(a.walletId(), b.walletId(), 100, PaymentType.PAYMENT, null, null),
                List.of(new Posting(a.walletId(), 0), new Posting(b.walletId(), 0)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Zero-amount");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(
                new Payment(a.walletId(), b.walletId(), 100, PaymentType.PAYMENT, null, null),
                List.of(new Posting(b.walletId(), 100)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least two entries");

        assertThat(paymentsTouching(a.walletId())).isEqualTo(paymentsBefore);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isZero();
    }

    @Test
    void ledgerRefusesToRunOutsideCallersTransaction() {
        Account a = register("CUSTOMER");
        Account b = register("MERCHANT");

        assertThatThrownBy(() -> ledger.transfer(new Payment(a.walletId(), b.walletId(), 100, PaymentType.PAYMENT, null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(paymentsTouching(a.walletId())).isZero();
    }

    private long paymentsTouching(long walletId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE payer_wallet_id = ? OR payee_wallet_id = ?",
                Long.class, walletId, walletId);
    }
}
