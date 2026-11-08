package com.intermanage.backend.dataentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.DataEntryInvoiceClosing;

public interface DataEntryInvoiceClosingRepository extends JpaRepository<DataEntryInvoiceClosing, UUID> {

    Optional<DataEntryInvoiceClosing> findByStoreIdAndSupplierCodeAndDocumentNumber(UUID storeId, String supplierCode,
            String documentNumber);

    List<DataEntryInvoiceClosing> findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId);

    List<DataEntryInvoiceClosing> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);
}
