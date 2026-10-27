package com.intermanage.backend.document.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.InvoiceComparisonMatch;

public interface InvoiceComparisonMatchRepository extends JpaRepository<InvoiceComparisonMatch, UUID> {

    @Query("""
            select m from InvoiceComparisonMatch m where m.document.id = :documentId and m.removedAt is null
            order by m.createdAt""")
    List<InvoiceComparisonMatch> findActive(UUID documentId);

    @Query("select m from InvoiceComparisonMatch m where m.id = :id and m.document.id = :documentId")
    Optional<InvoiceComparisonMatch> findInDocument(UUID documentId, UUID id);
}
