package com.payledger.dto;

import com.payledger.domain.Payment;
import com.payledger.domain.PaymentStatus;
import com.payledger.domain.PaymentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentRequest(
            @NotNull @Positive Long merchantWalletId,
            @NotNull @Positive @Max(WalletDtos.MAX_AMOUNT) Long amount,
            @Size(max = 140) String note) {
    }

    public record PaymentResponse(
            Long paymentId,
            PaymentType type,
            PaymentStatus status,
            Long payerWalletId,
            Long payeeWalletId,
            long amount,
            Long parentPaymentId,
            String note,
            Instant createdAt) {

        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getType(), p.getStatus(), p.getPayerWalletId(), p.getPayeeWalletId(),
                    p.getAmount(), p.getParentPaymentId(), p.getNote(), p.getCreatedAt());
        }
    }
}
