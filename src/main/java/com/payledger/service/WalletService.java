package com.payledger.service;

import com.payledger.domain.Payment;
import com.payledger.domain.PaymentType;
import com.payledger.domain.Wallet;
import com.payledger.dto.WalletDtos.TopUpResponse;
import com.payledger.dto.WalletDtos.WalletResponse;
import com.payledger.error.ApiException;
import com.payledger.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository wallets;
    private final LedgerService ledger;

    public WalletService(WalletRepository wallets, LedgerService ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    /**
     * Moves money from the SYSTEM wallet into the user's wallet, standing in for a bank load.
     *
     * NOTE: no wallet locks here. A credit can never overdraw the user, and the SYSTEM wallet has no
     * floor (it goes negative by design), so locking it would only serialise every top-up on one row.
     */
    @Transactional
    public TopUpResponse topUp(long userId, long amount) {
        Wallet wallet = walletOf(userId);
        Wallet system = wallets.findSystemWallet();
        Payment payment = ledger.transfer(new Payment(system.getId(), wallet.getId(), amount, PaymentType.TOPUP, null, null));
        return new TopUpResponse(payment.getId(), wallet.getId(), amount, ledger.balanceOf(wallet.getId()));
    }

    @Transactional(readOnly = true)
    public WalletResponse me(long userId) {
        Wallet wallet = walletOf(userId);
        return new WalletResponse(wallet.getId(), wallet.getCurrency(), ledger.balanceOf(wallet.getId()));
    }

    Wallet walletOf(long userId) {
        return wallets.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet for this user"));
    }
}
