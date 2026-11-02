package com.intermanage.backend.dataentry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.SupplierSalePrice;

public interface SupplierSalePriceRepository extends JpaRepository<SupplierSalePrice, UUID> {

    @EntityGraph(attributePaths = { "supplier", "createdBy" })
    List<SupplierSalePrice> findByStoreIdAndDeletedAtIsNull(UUID storeId);

    Optional<SupplierSalePrice> findBySupplierIdAndDeletedAtIsNull(UUID supplierId);
}
