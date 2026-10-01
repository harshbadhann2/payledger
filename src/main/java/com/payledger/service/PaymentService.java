package com.payledger.service;

import com.payledger.domain.Payment;
import com.payledger.domain.PaymentType;
import com.payledger.domain.Role;
import com.payledger.dto.PaymentDtos.PaymentRequest;
import com.payledger.dto.PaymentDtos.PaymentResponse;
import com.payledger.error.ApiException;
import com.payledger.repository.WalletRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final WalletRepository wallets;
    private final WalletService walletService;
    private final LedgerService ledger;

    public PaymentService(WalletRepository wallets, WalletService walletService, LedgerService ledger) {
        this.wallets = wallets;
        this.walletService = walletService;
        this.ledger = ledger;
    }

    /**
     * Pays a merchant from the caller's wallet in one transaction:
     * validate -> lock both wallets (ascending id) -> check balance under the lock -> write payment + 2 entries.
     */
    @Transactional
    public PaymentResponse pay(long userId, PaymentRequest request) {
        long payerId = walletService.walletOf(userId).getId();
        long payeeId = request.merchantWalletId();
        long amount = request.amount();

        if (payerId == payeeId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_PAYMENT", "You cannot pay your own wallet");
        }
        Role payeeRole = wallets.findOwnerRole(payeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Merchant wallet not found"));
        if (payeeRole != Role.MERCHANT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYEE", "Payments can only be made to merchant wallets");
        }

        lockInOrder(payerId, payeeId);

        // Read the balance only AFTER the lock: any debit that committed while we waited is now visible
        // (READ COMMITTED takes a fresh snapshot per statement), and no new debit can start until we finish.
        long balance = ledger.balanceOf(payerId);
        if (balance < amount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "Wallet balance is too low for this payment", Map.of("balance", balance, "amount", amount));
        }

        Payment payment = ledger.transfer(new Payment(payerId, payeeId, amount, PaymentType.PAYMENT, null, request.note()));
        return PaymentResponse.from(payment);
    }

    /**
     * Always lock the lower wallet id first. If A->B and B->A both locked "payer first" they could each
     * hold one lock and wait forever for the other (deadlock). A global order makes that cycle impossible.
     */
    void lockInOrder(long walletA, long walletB) {
        wallets.lockById(Math.min(walletA, walletB));
        wallets.lockById(Math.max(walletA, walletB));
    }
}
