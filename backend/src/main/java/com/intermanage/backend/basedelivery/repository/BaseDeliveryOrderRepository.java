package com.intermanage.backend.basedelivery.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.basedelivery.entity.BaseDeliveryOrder;

public interface BaseDeliveryOrderRepository extends JpaRepository<BaseDeliveryOrder, UUID> {

    @EntityGraph(attributePaths = "enteredBy")
    List<BaseDeliveryOrder> findByStoreIdAndBonNumberAndReplacedAtIsNullOrderBySupplierCode(UUID storeId,
            String bonNumber);

    Optional<BaseDeliveryOrder> findByStoreIdAndBonNumberAndSupplierCodeAndReplacedAtIsNull(UUID storeId,
            String bonNumber, String supplierCode);
}
