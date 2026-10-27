package com.intermanage.backend.deliorder.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliorder.entity.DeliOrderDocument;

public interface DeliOrderDocumentRepository extends JpaRepository<DeliOrderDocument, UUID> {

    /** Zamówienia garmażu, do których przypięto dokument (widok dokumentu w module Dokumenty). */
    @Query("""
            select l from DeliOrderDocument l join fetch l.order o join fetch o.store
            where l.document.id = :documentId and l.unlinkedAt is null order by o.deliveryDate, o.id""")
    List<DeliOrderDocument> findActiveByDocumentId(UUID documentId);
}
