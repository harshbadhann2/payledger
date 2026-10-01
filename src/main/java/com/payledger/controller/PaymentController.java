package com.payledger.controller;

import com.payledger.dto.PaymentDtos.PaymentRequest;
import com.payledger.service.IdempotencyService.IdempotentResponse;
import com.payledger.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<String> pay(@AuthenticationPrincipal Jwt jwt,
                                      @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
                                      @Valid @RequestBody PaymentRequest request) {
        return toResponse(paymentService.pay(Long.parseLong(jwt.getSubject()), idempotencyKey, request));
    }

    /** The body is the stored JSON string, so a replay is byte-for-byte the original response. */
    static ResponseEntity<String> toResponse(IdempotentResponse r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(r.replayed()))
                .body(r.body());
    }
}
