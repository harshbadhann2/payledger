package com.payledger.controller;

import com.payledger.dto.PaymentDtos.PaymentRequest;
import com.payledger.dto.PaymentDtos.PaymentResponse;
import com.payledger.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse pay(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentRequest request) {
        return paymentService.pay(Long.parseLong(jwt.getSubject()), request);
    }
}
