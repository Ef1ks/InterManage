package com.intermanage.backend.document.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentManualInvoiceFile;

/** Pliki faktur spoza KSeF; aktywny jest nieusunięty ({@code removedAt is null}), najwyżej jeden na fakturę. */
public interface DocumentManualInvoiceFileRepository extends JpaRepository<DocumentManualInvoiceFile, UUID> {

    /** Opis pliku bez treści, do list dokumentów. */
    interface Summary {
        UUID getInvoiceId();

        String getFileName();

        String getContentType();

        int getSizeBytes();

        Instant getUploadedAt();
    }

    @Query("""
            select f.invoice.id as invoiceId, f.fileName as fileName, f.contentType as contentType,
                f.sizeBytes as sizeBytes, f.createdAt as uploadedAt
            from DocumentManualInvoiceFile f where f.invoice.id in :invoiceIds and f.removedAt is null""")
    List<Summary> findActiveSummaries(Collection<UUID> invoiceIds);

    @Query("select f from DocumentManualInvoiceFile f where f.invoice.id = :invoiceId and f.removedAt is null")
    Optional<DocumentManualInvoiceFile> findActive(UUID invoiceId);

    Optional<DocumentManualInvoiceFile> findFirstByInvoiceIdAndRemovedAtIsNotNullOrderByRemovedAtDesc(UUID invoiceId);
}
