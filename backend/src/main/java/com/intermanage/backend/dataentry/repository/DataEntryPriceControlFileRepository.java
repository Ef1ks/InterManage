package com.intermanage.backend.dataentry.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;

/** Pliki CTRLPRIX z wprowadzania danych; lista czyta opis bez treści pliku. */
public interface DataEntryPriceControlFileRepository extends JpaRepository<DataEntryPriceControlFile, UUID> {

    interface Summary {
        UUID getId();

        String getFileName();

        String getSupplierCode();

        String getSupplierName();

        String getInvoiceNumber();

        int getLineCount();

        Instant getCreatedAt();

        String getCreatedBy();
    }

    @Query("""
            select f.id as id, f.fileName as fileName, f.supplierCode as supplierCode, f.supplierName as supplierName,
                   f.invoiceNumber as invoiceNumber, f.lineCount as lineCount, f.createdAt as createdAt,
                   u.displayName as createdBy
            from DataEntryPriceControlFile f left join f.createdBy u
            where f.storeId = :storeId
            order by f.createdAt desc, f.id desc""")
    List<Summary> findLatest(UUID storeId, Pageable page);

    Optional<DataEntryPriceControlFile> findByIdAndStoreId(UUID id, UUID storeId);
}
