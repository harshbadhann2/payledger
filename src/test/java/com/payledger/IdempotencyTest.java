package com.payledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void missingOrMalformedKeyIs400AndMovesNoMoney() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 1_000);
        Map<String, Object> body = Map.of("merchantWalletId", merchant.walletId(), "amount", 100);

        var missing = pay(customer, body, null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).containsEntry("code", "MISSING_HEADER");

        var malformed = pay(customer, body, "not-a-uuid");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody()).containsEntry("code", "INVALID_PARAMETER");

        assertThat(balance(customer)).isEqualTo(1_000);
    }

    @Test
    void sameKeySameBodyReplaysStoredResponseAndPaysOnce() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 1_000);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("merchantWalletId", merchant.walletId(), "amount", 300, "note", "Chai ☕");

        ResponseEntity<String> first = payRaw(customer, body, key);
        ResponseEntity<String> second = payRaw(customer, body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody()).contains("Chai ☕");
        assertThat(balance(customer)).isEqualTo(700);
        assertThat(balance(merchant)).isEqualTo(300);
        assertThat(paymentsFrom(customer)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs409AndMovesNoMoney() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 1_000);
        String key = UUID.randomUUID().toString();

        assertThat(pay(customer, Map.of("merchantWalletId", merchant.walletId(), "amount", 300), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        var reused = pay(customer, Map.of("merchantWalletId", merchant.walletId(), "amount", 999), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(customer)).isEqualTo(700);
        assertThat(paymentsFrom(customer)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerUser() {
        Account alice = register("CUSTOMER");
        Account bob = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(alice, 1_000);
        topUp(bob, 1_000);
        String sharedKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("merchantWalletId", merchant.walletId(), "amount", 100);

        assertThat(pay(alice, body, sharedKey).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(bob, body, sharedKey).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(balance(merchant)).isEqualTo(200);
    }

    @Test
    void failedPaymentDoesNotBurnTheKey() {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 100);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("merchantWalletId", merchant.walletId(), "amount", 150);

        assertThat(pay(customer, body, key).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        topUp(customer, 100);
        var retry = pay(customer, body, key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(customer)).isEqualTo(50);
    }

    @Test
    void tenConcurrentRequestsWithSameKeyCreateExactlyOnePayment() throws Exception {
        Account customer = register("CUSTOMER");
        Account merchant = register("MERCHANT");
        topUp(customer, 10_000);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("merchantWalletId", merchant.walletId(), "amount", 1_000);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return payRaw(customer, body, key);
            }));
        }
        start.countDown();
        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (var f : futures) {
            responses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(responses).allMatch(r -> r.getStatusCode().equals(HttpStatus.CREATED));
        assertThat(responses).extracting(ResponseEntity::getBody).containsOnly(responses.get(0).getBody());
        assertThat(responses).filteredOn(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed"))).hasSize(1);
        assertThat(paymentsFrom(customer)).isEqualTo(1);
        assertThat(balance(customer)).isEqualTo(9_000);
        assertThat(balance(merchant)).isEqualTo(1_000);
    }

    private ResponseEntity<String> payRaw(Account payer, Object body, String key) {
        var headers = headers(payer.token());
        headers.set("Idempotency-Key", key);
        return http.exchange("/api/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private long paymentsFrom(Account account) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE payer_wallet_id = ? AND type = 'PAYMENT'",
                Long.class, account.walletId());
    }
}
