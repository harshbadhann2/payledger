package com.payledger.controller;

import com.payledger.dto.WalletDtos.TopUpRequest;
import com.payledger.dto.WalletDtos.TopUpResponse;
import com.payledger.dto.WalletDtos.WalletResponse;
import com.payledger.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/topup")
    @ResponseStatus(HttpStatus.CREATED)
    public TopUpResponse topUp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TopUpRequest request) {
        return walletService.topUp(Long.parseLong(jwt.getSubject()), request.amount());
    }

    @GetMapping("/me")
    public WalletResponse me(@AuthenticationPrincipal Jwt jwt) {
        return walletService.me(Long.parseLong(jwt.getSubject()));
    }
}
