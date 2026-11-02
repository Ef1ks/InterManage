package com.intermanage.backend.deliproduction.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliproduction.entity.DocumentProductionCard;

public interface DocumentProductionCardRepository extends JpaRepository<DocumentProductionCard, UUID> {

    /** Karty przypięte do dokumentu (bez usuniętych), od najstarszej daty produkcji. */
    @Query("""
            select l from DocumentProductionCard l join fetch l.card c left join fetch l.linkedBy
            where l.document.id = :documentId and l.unlinkedAt is null and c.deletedAt is null
            order by c.productionDate, c.createdAt""")
    List<DocumentProductionCard> findActive(UUID documentId);

    @Query("""
            select l from DocumentProductionCard l
            where l.document.id = :documentId and l.card.id = :cardId and l.unlinkedAt is null""")
    Optional<DocumentProductionCard> findActive(UUID documentId, UUID cardId);
}
