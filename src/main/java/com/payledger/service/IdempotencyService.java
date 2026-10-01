package com.payledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a POST safe to retry. Runs inside the caller's transaction, so the key, the money movement and
 * the stored response commit together or not at all.
 *
 * The key row is inserted FIRST with ON CONFLICT DO NOTHING. If another transaction holds the same
 * (user, key), Postgres makes this insert wait until that transaction ends. If it committed, we replay
 * its stored response. If it rolled back (e.g. 422), the key is free again and this request runs.
 */
@Service
public class IdempotencyService {

    /** The JSON body and status to send. replayed=true means it came from an earlier request. */
    public record IdempotentResponse(int status, String body, boolean replayed) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotentResponse execute(long userId, UUID key, String endpoint, Object request, HttpStatus successStatus,
                                      Supplier<?> action) {
        String hash = hash(endpoint, request);
        int claimed = jdbc.update("""
                INSERT INTO idempotency_keys (user_id, key, request_hash) VALUES (?, ?, ?)
                ON CONFLICT (user_id, key) DO NOTHING
                """, userId, key, hash);

        if (claimed == 0) {
            Map<String, Object> stored = jdbc.queryForMap(
                    "SELECT request_hash, status_code, response_body FROM idempotency_keys WHERE user_id = ? AND key = ?",
                    userId, key);
            if (!hash.equals(stored.get("request_hash"))) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request");
            }
            return new IdempotentResponse((Integer) stored.get("status_code"), (String) stored.get("response_body"), true);
        }

        String body = toJson(action.get());
        jdbc.update("UPDATE idempotency_keys SET status_code = ?, response_body = ? WHERE user_id = ? AND key = ?",
                successStatus.value(), body, userId, key);
        return new IdempotentResponse(successStatus.value(), body, false);
    }

    /** SHA-256 over the endpoint and the canonical JSON of the validated request (stable field order). */
    String hash(String endpoint, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((endpoint + "\n" + toJson(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }
}
