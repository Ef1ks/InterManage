package com.intermanage.backend.dataentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.DataEntryInvoiceMark;
import com.intermanage.backend.dataentry.entity.InvoiceMarkKind;

public interface DataEntryInvoiceMarkRepository extends JpaRepository<DataEntryInvoiceMark, UUID> {

    /** Aktywne znaczniki faktur we wszystkich sklepach (własne i innych sklepów firmy). */
    List<DataEntryInvoiceMark> findByInvoiceIdInAndDeletedAtIsNull(Collection<UUID> invoiceIds);

    List<DataEntryInvoiceMark> findByStoreIdAndInvoiceIdAndDeletedAtIsNull(UUID storeId, UUID invoiceId);

    List<DataEntryInvoiceMark> findByStoreIdAndInvoiceIdInAndDeletedAtIsNull(UUID storeId, Collection<UUID> invoiceIds);

    List<DataEntryInvoiceMark> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);

    Optional<DataEntryInvoiceMark> findByStoreIdAndInvoiceIdAndKindAndDeletedAtIsNull(UUID storeId, UUID invoiceId,
            InvoiceMarkKind kind);
}
