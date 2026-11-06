package com.intermanage.backend.bakery.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.bakery.entity.BakeryWzScan;

public interface BakeryWzScanRepository extends JpaRepository<BakeryWzScan, UUID> {

    Optional<BakeryWzScan> findByIdAndStoreId(UUID id, UUID storeId);
}
