package com.payledger.repository;

import com.payledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** The only definition of a balance: the sum of the wallet's ledger entries. */
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.walletId = :walletId")
    long balanceOf(@Param("walletId") Long walletId);
}
