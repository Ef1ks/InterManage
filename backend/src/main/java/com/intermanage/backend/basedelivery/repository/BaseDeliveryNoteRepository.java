package com.intermanage.backend.basedelivery.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.basedelivery.entity.BaseDeliveryNote;

public interface BaseDeliveryNoteRepository extends JpaRepository<BaseDeliveryNote, UUID> {

    List<BaseDeliveryNote> findByStoreIdAndBonNumberAndReplacedAtIsNull(UUID storeId, String bonNumber);

    Optional<BaseDeliveryNote> findByStoreIdAndBonNumberAndSupplierCodeAndReplacedAtIsNull(UUID storeId,
            String bonNumber, String supplierCode);
}
