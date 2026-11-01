package com.intermanage.backend.dataentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.DataEntryProductLink;

public interface DataEntryProductLinkRepository extends JpaRepository<DataEntryProductLink, UUID> {

    Optional<DataEntryProductLink> findByStoreIdAndSellerNipAndInvoiceCodeAndDeletedAtIsNull(UUID storeId,
            String sellerNip, String invoiceCode);

    Optional<DataEntryProductLink> findByIdAndStoreId(UUID id, UUID storeId);

    interface ActiveLink {
        UUID getId();

        String getCode();

        String getEan();
    }

    /** Aktywne przypięcia kodów dostawcy z bieżącym EAN-em artykułu. */
    @Query("""
            select l.id as id, l.invoiceCode as code, p.ean as ean
            from DataEntryProductLink l join l.product p
            where l.store.id = :storeId and l.sellerNip = :sellerNip and l.deletedAt is null
                and l.invoiceCode in :codes""")
    List<ActiveLink> findActive(UUID storeId, String sellerNip, Collection<String> codes);
}
