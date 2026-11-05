package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentTransferTarget;

public interface DocumentTransferTargetRepository extends JpaRepository<DocumentTransferTarget, UUID> {

    @Query("""
            select t from DocumentTransferTarget t join fetch t.targetStore
            left join fetch t.createdBy left join fetch t.enteredBy
            where t.document.id = :documentId and t.deletedAt is null""")
    Optional<DocumentTransferTarget> findActive(UUID documentId);

    /** Sklepy docelowe dokumentów ze strony listy, jednym zapytaniem. */
    @Query("""
            select t from DocumentTransferTarget t join fetch t.targetStore
            left join fetch t.createdBy left join fetch t.enteredBy
            where t.document.id in :documentIds and t.deletedAt is null""")
    List<DocumentTransferTarget> findActive(Collection<UUID> documentIds);

    /** Ostatnio cofnięty sklep docelowy dokumentu (do "Przywróć"). */
    Optional<DocumentTransferTarget> findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID documentId);

    /** Ruchy międzymagazynowe innych sklepów bez przypiętego dokumentu przyjęcia, od najstarszego. */
    @Query("""
            select t from DocumentTransferTarget t join fetch t.document d join fetch d.store
            left join fetch t.createdBy left join fetch t.enteredBy
            where t.targetStore.id = :storeId and t.deletedAt is null and not exists (
                select 1 from DocumentTransferReceipt r where r.target = t and r.unlinkedAt is null)
            order by d.documentDate, d.number""")
    List<DocumentTransferTarget> findPending(UUID storeId);

    /** Ostatnio potwierdzone ruchy międzymagazynowe innych sklepów (przypięty dokument przyjęcia). */
    @Query("""
            select t from DocumentTransferTarget t join fetch t.document d join fetch d.store
            left join fetch t.createdBy left join fetch t.enteredBy
            join DocumentTransferReceipt r on r.target = t and r.unlinkedAt is null
            where t.targetStore.id = :storeId and t.deletedAt is null
            order by r.linkedAt desc""")
    List<DocumentTransferTarget> findEntered(UUID storeId, Limit limit);

    @Query("""
            select count(t) from DocumentTransferTarget t
            where t.targetStore.id = :storeId and t.deletedAt is null and not exists (
                select 1 from DocumentTransferReceipt r where r.target = t and r.unlinkedAt is null)""")
    long countPending(UUID storeId);

    @Query("""
            select t from DocumentTransferTarget t join fetch t.document d join fetch d.store
            left join fetch t.createdBy left join fetch t.enteredBy
            where t.id = :id and t.targetStore.id = :storeId and t.deletedAt is null""")
    Optional<DocumentTransferTarget> findIncoming(UUID storeId, UUID id);
}
