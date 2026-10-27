package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentKsefInvoice;

/** Przypięcia dokumentów do faktur KSeF; liczą się tylko nieodpięte ({@code unlinkedAt is null}). */
public interface DocumentKsefInvoiceRepository extends JpaRepository<DocumentKsefInvoice, UUID> {

    @Query("""
            select l from DocumentKsefInvoice l join fetch l.invoice
            where l.document.id in :documentIds and l.unlinkedAt is null order by l.linkedAt""")
    List<DocumentKsefInvoice> findActiveByDocumentIds(Collection<UUID> documentIds);

    /** Dokumenty sklepu przypięte do faktur (faktury są wspólne dla firmy, przypięcia - sklepu). */
    @Query("""
            select l from DocumentKsefInvoice l join fetch l.document d
            where d.store.id = :storeId and l.invoice.id in :invoiceIds and l.unlinkedAt is null
            order by d.documentDate""")
    List<DocumentKsefInvoice> findActiveByInvoiceIds(UUID storeId, Collection<UUID> invoiceIds);

    /** Dokumenty podanych sklepów (sklepów firmy) przypięte do faktur, razem ze sklepem. */
    @Query("""
            select l from DocumentKsefInvoice l join fetch l.document d join fetch d.store
            where d.store.id in :storeIds and l.invoice.id in :invoiceIds and l.unlinkedAt is null
            order by d.documentDate""")
    List<DocumentKsefInvoice> findActiveByInvoiceIdsInStores(Collection<UUID> storeIds, Collection<UUID> invoiceIds);

    @Query("""
            select l from DocumentKsefInvoice l
            where l.document.id = :documentId and l.invoice.id = :invoiceId and l.unlinkedAt is null""")
    Optional<DocumentKsefInvoice> findActive(UUID documentId, UUID invoiceId);
}
