package com.intermanage.backend.cash.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.cash.entity.CashStamp;

public interface CashStampRepository extends JpaRepository<CashStamp, UUID> {

    Optional<CashStamp> findByStoreIdAndRemovedAtIsNull(UUID storeId);

    Optional<CashStamp> findByIdAndStoreId(UUID id, UUID storeId);
}
