package com.payledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void happyPathMovesMoneyFromCustomerToMerchant() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 10_000);

        var response = post("/api/payments",
                Map.of("merchantWalletId", merchant.walletId(), "amount", 2_500, "note", "Order #17"), customer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("type", "PAYMENT")
                .containsEntry("status", "COMPLETED")
                .containsEntry("amount", 2_500)
                .containsEntry("note", "Order #17");
        assertThat(asLong(response.getBody().get("payerWalletId"))).isEqualTo(customer.walletId());
        assertThat(asLong(response.getBody().get("payeeWalletId"))).isEqualTo(merchant.walletId());
        assertThat(balance(customer)).isEqualTo(7_500);
        assertThat(balance(merchant)).isEqualTo(2_500);

        long paymentId = asLong(response.getBody().get("paymentId"));
        assertThat(jdbc.queryForList("SELECT wallet_id, amount FROM ledger_entries WHERE payment_id = ? ORDER BY amount",
                paymentId)).containsExactly(
                Map.of("wallet_id", customer.walletId(), "amount", -2_500L),
                Map.of("wallet_id", merchant.walletId(), "amount", 2_500L));
    }

    @Test
    void insufficientFundsIs422AndWritesNothing() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 100);

        var response = pay(customer, merchant.walletId(), 150);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_FUNDS")
                .containsEntry("details", Map.of("balance", 100, "amount", 150));
        assertThat(balance(customer)).isEqualTo(100);
        assertThat(balance(merchant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE payer_wallet_id = ? AND type = 'PAYMENT'",
                Long.class, customer.walletId())).isZero();
    }

    @Test
    void payingExactlyTheWholeBalanceSucceeds() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 150);

        assertThat(pay(customer, merchant.walletId(), 150).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(customer)).isZero();
    }

    @Test
    void selfPaymentIs400() {
        Account merchant = register("MERCHANT");
        topUp(merchant, 1_000);

        var response = pay(merchant, merchant.walletId(), 100);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SELF_PAYMENT");
        assertThat(balance(merchant)).isEqualTo(1_000);
    }

    @Test
    void payeeMustBeAMerchant() {
        Account customer = register("CUSTOMER");
        Account otherCustomer = register("CUSTOMER");
        topUp(customer, 1_000);

        var response = pay(customer, otherCustomer.walletId(), 100);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_PAYEE");
        assertThat(balance(otherCustomer)).isZero();
    }

    @Test
    void unknownMerchantWalletIs404() {
        Account customer = register("CUSTOMER");
        topUp(customer, 1_000);

        var response = pay(customer, Long.MAX_VALUE, 100);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
    }

    @Test
    void invalidRequestsAre400() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");

        assertThat(pay(customer, merchant.walletId(), 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(customer, merchant.walletId(), -1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/payments", Map.of("amount", 100), customer.token()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/payments", Map.of("merchantWalletId", merchant.walletId(), "amount", 100, "note", "x".repeat(141)),
                customer.token()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(new Account(0, 0, "", null), merchant.walletId(), 100).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The core correctness test. 100 threads try to spend 150 paise each from a 10000-paise wallet at
     * the same instant. Only floor(10000 / 150) = 66 can succeed; the wallet must end at exactly 100.
     */
    @Test
    void concurrentPaymentsNeverOverdraw() throws Exception {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 10_000);

        List<HttpStatusCode> statuses = runConcurrently(100, i -> () -> pay(customer, merchant.walletId(), 150).getStatusCode());

        assertThat(count(statuses, HttpStatus.CREATED)).isEqualTo(66);
        assertThat(count(statuses, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(34);
        assertThat(balance(customer)).isEqualTo(100);
        assertThat(balance(merchant)).isEqualTo(9_900);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entries", Long.class)).isZero();
    }

    /**
     * A pays B while B pays A, 50 times each, all at once. Locking "payer first" would deadlock here
     * (Postgres would abort one side with an error); ascending-id lock order makes every request succeed.
     */
    @Test
    void merchantsPayingEachOtherConcurrentlyDoNotDeadlock() throws Exception {
        Account a = register("MERCHANT");
        Account b = register("MERCHANT");
        topUp(a, 100_000);
        topUp(b, 100_000);

        List<HttpStatusCode> statuses = runConcurrently(100, i -> i % 2 == 0
                ? () -> pay(a, b.walletId(), 100).getStatusCode()
                : () -> pay(b, a.walletId(), 100).getStatusCode());

        assertThat(statuses).hasSize(100).allMatch(s -> s.equals(HttpStatus.CREATED));
        assertThat(balance(a)).isEqualTo(100_000);
        assertThat(balance(b)).isEqualTo(100_000);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entries", Long.class)).isZero();
    }

    /** Starts every task at the same instant (one latch) and waits for all, failing on a hang. */
    private static <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<HttpStatusCode> statuses, HttpStatus status) {
        Map<HttpStatusCode, Long> counts = new HashMap<>();
        statuses.forEach(s -> counts.merge(s, 1L, Long::sum));
        return counts.getOrDefault(status, 0L);
    }
}
