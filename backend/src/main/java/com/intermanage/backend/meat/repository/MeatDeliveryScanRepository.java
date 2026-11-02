package com.intermanage.backend.meat.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.meat.entity.MeatDeliveryScan;

public interface MeatDeliveryScanRepository extends JpaRepository<MeatDeliveryScan, UUID> {

    Optional<MeatDeliveryScan> findByIdAndStoreId(UUID id, UUID storeId);
}
