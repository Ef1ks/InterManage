package com.intermanage.backend.supplier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.supplier.entity.MercalysSupplierCreation;

public interface MercalysSupplierCreationRepository extends JpaRepository<MercalysSupplierCreation, UUID> {

    List<MercalysSupplierCreation> findByStoreIdAndDeletedAtIsNull(UUID storeId);

    Optional<MercalysSupplierCreation> findFirstByStoreIdAndNipAndDeletedAtIsNullOrderByCreatedAtDesc(UUID storeId,
            String nip);

    Optional<MercalysSupplierCreation> findByIdAndStoreId(UUID id, UUID storeId);
}
