package com.intermanage.backend.cash.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.cash.entity.CashOpeningBalance;

public interface CashOpeningBalanceRepository extends JpaRepository<CashOpeningBalance, UUID> {

    Optional<CashOpeningBalance> findByStoreIdAndRemovedAtIsNull(UUID storeId);
}
