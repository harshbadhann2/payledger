package com.payledger.service;

import com.payledger.domain.LedgerEntry;
import com.payledger.domain.Payment;
import com.payledger.repository.LedgerEntryRepository;
import com.payledger.repository.PaymentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single write path for money. MANDATORY propagation means it can only run inside the caller's
 * transaction, so the caller's locks, balance check and these writes commit or roll back together.
 */
@Service
public class LedgerService {

    /** One leg of a posting: signed paise against a wallet. */
    public record Posting(long walletId, long amount) {
    }

    private final PaymentRepository payments;
    private final LedgerEntryRepository entries;

    public LedgerService(PaymentRepository payments, LedgerEntryRepository entries) {
        this.payments = payments;
        this.entries = entries;
    }

    /** Debits the payer and credits the payee by the payment amount. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment transfer(Payment payment) {
        return post(payment, List.of(
                new Posting(payment.getPayerWalletId(), -payment.getAmount()),
                new Posting(payment.getPayeeWalletId(), payment.getAmount())));
    }

    /** Validates the postings before writing anything; throws if they do not sum to zero. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment post(Payment payment, List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalStateException("A posting needs at least two entries, got " + postings.size());
        }
        long sum = 0;
        for (Posting p : postings) {
            if (p.amount() == 0) {
                throw new IllegalStateException("Zero-amount entry for wallet " + p.walletId());
            }
            sum = Math.addExact(sum, p.amount());
        }
        if (sum != 0) {
            throw new IllegalStateException("Ledger entries must sum to zero, got " + sum);
        }

        Payment saved = payments.save(payment);
        entries.saveAll(postings.stream()
                .map(p -> new LedgerEntry(p.walletId(), saved.getId(), p.amount(), saved.getCreatedAt()))
                .toList());
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long balanceOf(long walletId) {
        return entries.balanceOf(walletId);
    }
}
