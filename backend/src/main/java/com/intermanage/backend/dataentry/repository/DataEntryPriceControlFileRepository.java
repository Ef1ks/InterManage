package com.intermanage.backend.dataentry.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;

/** Pliki CTRLPRIX z wprowadzania danych; listy czytają opis bez treści pliku i bez usuniętych. */
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

        Instant getLoadedAt();

        boolean isLoadedManually();

        Integer getProcessedCount();

        String getLoadedBy();
    }

    String SUMMARY = """
            select f.id as id, f.fileName as fileName, f.supplierCode as supplierCode, f.supplierName as supplierName,
                   f.invoiceNumber as invoiceNumber, f.lineCount as lineCount, f.createdAt as createdAt,
                   u.displayName as createdBy, f.loadedAt as loadedAt, f.loadedManually as loadedManually,
                   f.processedCount as processedCount, l.displayName as loadedBy
            from DataEntryPriceControlFile f left join f.createdBy u left join f.loadedBy l
            where f.storeId = :storeId and f.deletedAt is null""";

    /** Pliki, których program sklepu jeszcze nie wczytał, od najstarszego (wszystkie, bo każdy trzeba wgrać). */
    @Query(SUMMARY + " and f.loadedAt is null order by f.createdAt, f.id")
    List<Summary> findPending(UUID storeId);

    /** Ostatnie wgrane pliki, od najnowszego. */
    @Query(SUMMARY + " and f.loadedAt is not null order by f.createdAt desc, f.id desc")
    List<Summary> findLoaded(UUID storeId, Pageable page);

    @Query("select count(f) from DataEntryPriceControlFile f where f.storeId = :storeId and f.deletedAt is null and f.loadedAt is null")
    long countPending(UUID storeId);

    List<DataEntryPriceControlFile> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);

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
