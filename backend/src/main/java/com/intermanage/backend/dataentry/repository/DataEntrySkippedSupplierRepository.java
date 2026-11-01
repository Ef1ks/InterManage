package com.intermanage.backend.dataentry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.DataEntrySkippedSupplier;

public interface DataEntrySkippedSupplierRepository extends JpaRepository<DataEntrySkippedSupplier, UUID> {

    List<DataEntrySkippedSupplier> findByStoreIdAndDeletedAtIsNullOrderBySellerNameAsc(UUID storeId);

    Optional<DataEntrySkippedSupplier> findByStoreIdAndSellerNipAndDeletedAtIsNull(UUID storeId, String sellerNip);

    Optional<DataEntrySkippedSupplier> findByIdAndStoreId(UUID id, UUID storeId);
}
