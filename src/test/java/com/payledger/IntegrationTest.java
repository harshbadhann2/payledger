package com.payledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Base for every test: one real Postgres container and one Spring context, shared through the
 * context cache. Tests never clean up; each one creates its own users and asserts on its own data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payledger.jwt.secret=test-only-secret-that-is-at-least-32-bytes-long")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    protected TestRestTemplate http;

    /** A registered, logged-in user. */
    protected record Account(long userId, long walletId, String email, String token) {
    }

    protected Account register(String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.local";
        String password = "Passw0rd!";
        var reg = post("/api/auth/register", Map.of("email", email, "password", password, "role", role), null);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var login = post("/api/auth/login", Map.of("email", email, "password", password), null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Account(asLong(reg.getBody().get("userId")), asLong(reg.getBody().get("walletId")), email,
                (String) login.getBody().get("accessToken"));
    }

    protected ResponseEntity<Map<String, Object>> post(String path, Object body, String token) {
        return exchange(HttpMethod.POST, path, body, headers(token));
    }

    protected ResponseEntity<Map<String, Object>> get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, headers(token));
    }

    protected ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, Object body, HttpHeaders headers) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), JSON);
    }

    protected static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    protected static long asLong(Object number) {
        return ((Number) number).longValue();
    }
}
