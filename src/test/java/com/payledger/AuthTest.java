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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AuthTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void registerCreatesUserAndWalletTogether() {
        String email = "New-" + UUID.randomUUID() + "@Test.local";
        var response = post("/api/auth/register", Map.of("email", email, "password", "secret123", "role", "MERCHANT"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).containsEntry("email", email.toLowerCase()).containsEntry("role", "MERCHANT");
        assertThat(body).doesNotContainKeys("password", "passwordHash");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT w.id AS wallet_id, w.currency, u.password_hash FROM users u JOIN wallets w ON w.user_id = u.id
                WHERE u.id = ?
                """, asLong(body.get("userId")));
        assertThat(asLong(row.get("wallet_id"))).isEqualTo(asLong(body.get("walletId")));
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat((String) row.get("password_hash")).startsWith("$2").isNotEqualTo("secret123");
    }

    @Test
    void loginReturnsSignedJwtWithUserIdAndRole() {
        Account account = register("CUSTOMER");

        var jwt = jwtDecoder.decode(account.token());

        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(account.userId()));
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void loginIgnoresEmailCase() {
        Account account = register("CUSTOMER");

        var response = post("/api/auth/login", Map.of("email", account.email().toUpperCase(), "password", "Passw0rd!"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void duplicateEmailIs409EvenWithDifferentCase() {
        Account existing = register("CUSTOMER");

        var response = post("/api/auth/register",
                Map.of("email", existing.email().toUpperCase(), "password", "another123", "role", "MERCHANT"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "EMAIL_TAKEN");
    }

    @Test
    void concurrentRegistrationsWithSameEmailCreateExactlyOneUser() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@test.local";
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post("/api/auth/register", Map.of("email", email, "password", "secret123", "role", "CUSTOMER"), null)
                        .getStatusCode();
            }));
        }
        start.countDown();
        List<HttpStatusCode> statuses = new ArrayList<>();
        for (Future<HttpStatusCode> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s.equals(HttpStatus.CREATED)).hasSize(1);
        assertThat(statuses).filteredOn(s -> s.equals(HttpStatus.CONFLICT)).hasSize(threads - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1);
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable401() {
        Account account = register("CUSTOMER");

        var wrongPassword = post("/api/auth/login", Map.of("email", account.email(), "password", "Wrong1234"), null);
        var unknownEmail = post("/api/auth/login", Map.of("email", "nobody-" + UUID.randomUUID() + "@x.io", "password", "Wrong1234"), null);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
        assertThat(wrongPassword.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void systemAccountCannotLogIn() {
        var response = post("/api/auth/login", Map.of("email", "system@payledger.internal", "password", "!"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void weakOrInvalidRegistrationsAre400() {
        String email = "weak-" + UUID.randomUUID() + "@test.local";
        assertValidationError(Map.of("email", email, "password", "short1", "role", "CUSTOMER"), "password");
        assertValidationError(Map.of("email", email, "password", "onlyletters", "role", "CUSTOMER"), "password");
        assertValidationError(Map.of("email", email, "password", "1234567890", "role", "CUSTOMER"), "password");
        assertValidationError(Map.of("email", email, "password", "a" + "ä1".repeat(30), "role", "CUSTOMER"), "passwordWithinBcryptLimit");
        assertValidationError(Map.of("email", "not-an-email", "password", "secret123", "role", "CUSTOMER"), "email");
        assertValidationError(Map.of("email", email, "password", "secret123", "role", "SYSTEM"), "registrableRole");
        assertValidationError(Map.of("email", email, "password", "secret123"), "role");

        var badRole = post("/api/auth/register", Map.of("email", email, "password", "secret123", "role", "ADMIN"), null);
        assertThat(badRole.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRole.getBody()).containsEntry("code", "MALFORMED_REQUEST");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email)).isZero();
    }

    @Test
    void protectedEndpointWithoutValidTokenIs401() {
        assertThat(get("/api/wallets/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/wallets/me", "not.a.jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String token = register("CUSTOMER").token();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThat(get("/api/wallets/me", tampered).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertValidationError(Map<String, Object> body, String field) {
        var response = post("/api/auth/register", body, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) response.getBody().get("details");
        assertThat(details).containsKey(field);
    }
}
