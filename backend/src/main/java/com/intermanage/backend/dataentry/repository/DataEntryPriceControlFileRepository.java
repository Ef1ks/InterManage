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

    interface WithContent {
        String getFileName();

        String getSupplierCode();

        String getSupplierName();

        String getInvoiceNumber();

        Instant getCreatedAt();

        String getCreatedBy();

        byte[] getContent();
    }

    /**
     * Pliki sklepu, w których treści jest któryś z kodów (EAN-y na 13 znakach jak w pliku), od najnowszego. Treść
     * wraca, bo trafienie trzeba sprawdzić w liniach (kod mógłby wypaść w środku ceny).
     */
    @Query(value = """
            select f.file_name as fileName, f.supplier_code as supplierCode, f.supplier_name as supplierName,
                   f.invoice_number as invoiceNumber, f.created_at as createdAt, u.display_name as createdBy,
                   f.content as content
            from data_entry_price_control_files f
            left join users u on u.id = f.created_by
            where f.store_id = :storeId
              and exists (select 1 from unnest(cast(:codes as text[])) code
                          where position(convert_to(code, 'UTF8') in f.content) > 0)
            order by f.created_at desc
            limit :limit
            """, nativeQuery = true)
    List<WithContent> findContaining(UUID storeId, String[] codes, int limit);
}
