package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentAmountConfirmation;

/** Potwierdzone różnice kwot przyjęć i faktur; liczą się tylko niecofnięte. */
public interface DocumentAmountConfirmationRepository extends JpaRepository<DocumentAmountConfirmation, UUID> {

    @Query("""
            select c from DocumentAmountConfirmation c left join fetch c.confirmedBy
            where c.document.id in :documentIds and c.cancelledAt is null""")
    List<DocumentAmountConfirmation> findActive(Collection<UUID> documentIds);

    @Query("select c from DocumentAmountConfirmation c where c.document.id = :documentId and c.cancelledAt is null")
    Optional<DocumentAmountConfirmation> findActive(UUID documentId);
}
