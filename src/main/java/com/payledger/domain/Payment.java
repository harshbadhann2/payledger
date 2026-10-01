package com.payledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.Immutable;

/** A money movement from payer to payee. Append-only: Hibernate never updates it, and a DB trigger rejects updates. */
@Entity
@Immutable
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payer_wallet_id", nullable = false)
    private Long payerWalletId;

    @Column(name = "payee_wallet_id", nullable = false)
    private Long payeeWalletId;

    /** Paise, always positive. Direction comes from payer -> payee. */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentType type;

    @Column(name = "parent_payment_id")
    private Long parentPaymentId;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(Long payerWalletId, Long payeeWalletId, long amount, PaymentType type, Long parentPaymentId,
                   String note) {
        this.payerWalletId = payerWalletId;
        this.payeeWalletId = payeeWalletId;
        this.amount = amount;
        this.status = PaymentStatus.COMPLETED;
        this.type = type;
        this.parentPaymentId = parentPaymentId;
        this.note = note;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getPayerWalletId() {
        return payerWalletId;
    }

    public Long getPayeeWalletId() {
        return payeeWalletId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentType getType() {
        return type;
    }

    public Long getParentPaymentId() {
        return parentPaymentId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
