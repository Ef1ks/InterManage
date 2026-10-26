package com.intermanage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.KsefInvoiceEntry;

public interface KsefInvoiceEntryRepository extends JpaRepository<KsefInvoiceEntry, KsefInvoiceEntry.Key> {

    @Query("""
            select e from KsefInvoiceEntry e left join fetch e.enteredBy
            where e.id.storeId = :storeId and e.id.invoiceId in :invoiceIds""")
    List<KsefInvoiceEntry> findInStore(UUID storeId, Collection<UUID> invoiceIds);
}
