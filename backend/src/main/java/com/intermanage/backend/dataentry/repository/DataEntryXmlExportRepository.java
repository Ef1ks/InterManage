package com.intermanage.backend.dataentry.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.entity.DataEntryXmlExport;
import com.intermanage.backend.dataentry.entity.XmlExportKind;

/** Pliki XML faktur z wprowadzania danych; listy czytają opis bez treści pliku. */
public interface DataEntryXmlExportRepository extends JpaRepository<DataEntryXmlExport, UUID> {

    interface Summary {
        UUID getId();

        String getFileName();

        String getDocumentNumber();

        LocalDate getDocumentDate();

        String getSupplierName();

        String getSupplierNip();

        int getLineCount();

        BigDecimal getNetValue();

        Instant getCreatedAt();

        String getCreatedBy();
    }

    /** Historia "Zapisz XML": tylko pliki zapisane na dysku. */
    @Query("""
            select x.id as id, x.fileName as fileName, x.documentNumber as documentNumber,
                   x.documentDate as documentDate, x.supplierName as supplierName, x.supplierNip as supplierNip,
                   x.lineCount as lineCount, x.netValue as netValue, x.savedAt as createdAt,
                   u.displayName as createdBy
            from DataEntryXmlExport x left join x.createdBy u
            where x.storeId = :storeId and x.kind = com.intermanage.backend.dataentry.entity.XmlExportKind.SAVED
              and x.savedAt is not null
            order by x.savedAt desc, x.id desc""")
    List<Summary> findSaved(UUID storeId, Pageable page);

    /** Zapisy tymczasowe bez znacznika usunięcia. */
    @Query("""
            select x.id as id, x.fileName as fileName, x.documentNumber as documentNumber,
                   x.documentDate as documentDate, x.supplierName as supplierName, x.supplierNip as supplierNip,
                   x.lineCount as lineCount, x.netValue as netValue, x.createdAt as createdAt,
                   u.displayName as createdBy
            from DataEntryXmlExport x left join x.createdBy u
            where x.storeId = :storeId and x.kind = com.intermanage.backend.dataentry.entity.XmlExportKind.TEMPORARY
              and x.deletedAt is null
            order by x.createdAt desc, x.id desc""")
    List<Summary> findTemporary(UUID storeId, Pageable page);

    List<DataEntryXmlExport> findByStoreIdAndKindAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId,
            XmlExportKind kind);

    Optional<DataEntryXmlExport> findByIdAndStoreId(UUID id, UUID storeId);
}
