package com.intermanage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.DocumentGratis;

public interface DocumentGratisRepository extends JpaRepository<DocumentGratis, UUID> {

    @Query("""
            select g from DocumentGratis g join fetch g.supplier left join fetch g.registeredBy
            where g.document.id = :documentId and g.deletedAt is null""")
    Optional<DocumentGratis> findActive(UUID documentId);

    /** Gratisy dokumentów ze strony listy, jednym zapytaniem. */
    @Query("""
            select g from DocumentGratis g join fetch g.supplier left join fetch g.registeredBy
            where g.document.id in :documentIds and g.deletedAt is null""")
    List<DocumentGratis> findActive(Collection<UUID> documentIds);

    /** Ostatnio cofnięty gratis dokumentu (do "Przywróć"). */
    Optional<DocumentGratis> findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID documentId);
}
