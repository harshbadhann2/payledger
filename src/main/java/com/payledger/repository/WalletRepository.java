package com.payledger.repository;

import com.payledger.domain.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    @Query("select w from Wallet w join User u on u.id = w.userId where u.role = com.payledger.domain.Role.SYSTEM")
    Wallet findSystemWallet();
}
