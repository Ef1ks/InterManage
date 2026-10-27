package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentManualInvoice;

/** Faktury spoza KSeF przy dokumentach; liczą się tylko nieusunięte ({@code removedAt is null}). */
public interface DocumentManualInvoiceRepository extends JpaRepository<DocumentManualInvoice, UUID> {

    @Query("""
            select i from DocumentManualInvoice i join fetch i.supplier
            where i.document.id in :documentIds and i.removedAt is null order by i.createdAt""")
    List<DocumentManualInvoice> findActiveByDocumentIds(Collection<UUID> documentIds);

    @Query("select i from DocumentManualInvoice i where i.id = :id and i.document.id = :documentId")
    Optional<DocumentManualInvoice> findInDocument(UUID documentId, UUID id);
}
