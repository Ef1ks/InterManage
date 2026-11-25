package com.intermanage.backend.dataentry.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;

import jakarta.persistence.LockModeType;

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

        Instant getMacroRequestedAt();

        UUID getMacroJobId();

        /** Zmianę wprowadzi sam komputer sklepu, gdy będzie mógł. */
        default boolean isMacroWaiting() {
            return getMacroRequestedAt() != null && getMacroJobId() == null && getLoadedAt() == null
                    && getMacroRequestedAt().isAfter(Instant.now().minus(DataEntryPriceControlFile.MACRO_WAIT));
        }
    }

    String SUMMARY = """
            select f.id as id, f.fileName as fileName, f.supplierCode as supplierCode, f.supplierName as supplierName,
                   f.invoiceNumber as invoiceNumber, f.lineCount as lineCount, f.createdAt as createdAt,
                   u.displayName as createdBy, f.loadedAt as loadedAt, f.loadedManually as loadedManually,
                   f.processedCount as processedCount, l.displayName as loadedBy,
                   f.macroRequestedAt as macroRequestedAt, f.macroJobId as macroJobId
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

    List<DataEntryPriceControlFile> findByStoreIdAndFileName(UUID storeId, String fileName);

    List<DataEntryPriceControlFile> findByMacroJobId(UUID macroJobId);

    String WAITING_FOR_MACRO = """
            f.macroRequestedAt > :since and f.macroJobId is null and f.loadedAt is null and f.deletedAt is null
              and (f.macroRetryAt is null or f.macroRetryAt <= :now)""";

    /** Zmiany cen czekające na makro, od najstarszej (czas jest na początku nazwy). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from DataEntryPriceControlFile f where f.storeId = :storeId and " + WAITING_FOR_MACRO
            + " order by f.fileName")
    List<DataEntryPriceControlFile> lockWaitingForMacro(UUID storeId, Instant since, Instant now);

    @Query("select distinct f.storeId from DataEntryPriceControlFile f where " + WAITING_FOR_MACRO)
    List<UUID> findStoresWaitingForMacro(Instant since, Instant now);

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
