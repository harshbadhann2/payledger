package com.payledger.repository;

import com.payledger.domain.Role;
import com.payledger.domain.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    @Query("select w from Wallet w join User u on u.id = w.userId where u.role = com.payledger.domain.Role.SYSTEM")
    Wallet findSystemWallet();

    /** Role of the user owning the wallet; empty if the wallet does not exist. */
    @Query("select u.role from Wallet w join User u on u.id = w.userId where w.id = :walletId")
    Optional<Role> findOwnerRole(@Param("walletId") Long walletId);

    /**
     * Row lock held until the transaction ends. Every money movement that debits a wallet takes this
     * lock first, so two debits of the same wallet run one after the other, never interleaved.
     */
    @Query(value = "SELECT * FROM wallets WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Wallet> lockById(@Param("id") Long id);
}
