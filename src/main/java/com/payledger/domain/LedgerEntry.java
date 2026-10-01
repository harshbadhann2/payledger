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
import org.hibernate.annotations.Immutable;

/** One leg of a double-entry posting. Signed paise: DEBIT is negative, CREDIT is positive. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long walletId, Long paymentId, long amount, Instant createdAt) {
        this.walletId = walletId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.entryType = amount < 0 ? EntryType.DEBIT : EntryType.CREDIT;
        this.createdAt = createdAt;
    }

    public Long getWalletId() {
        return walletId;
    }

    public long getAmount() {
        return amount;
    }

    public EntryType getEntryType() {
        return entryType;
    }
}
