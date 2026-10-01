package com.payledger.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class WalletDtos {

    /** Largest single top-up: ₹1,00,00,000 (one crore) in paise. */
    public static final long MAX_AMOUNT = 1_000_000_000L;

    private WalletDtos() {
    }

    public record TopUpRequest(@NotNull @Positive @Max(MAX_AMOUNT) Long amount) {
    }

    public record TopUpResponse(Long paymentId, Long walletId, long amount, long balance) {
    }

    public record WalletResponse(Long walletId, String currency, long balance) {
    }
}
