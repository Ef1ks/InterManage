package com.intermanage.backend.deliproduction.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliproduction.entity.ProductionCard;

public interface ProductionCardRepository extends JpaRepository<ProductionCard, UUID> {

    /** Karty sklepów z okresu (data produkcji), od najnowszej. */
    @Query("""
            select c from ProductionCard c left join fetch c.createdBy
            where c.storeId in :storeIds and c.deletedAt is null and c.productionDate between :from and :to
            order by c.productionDate desc, c.createdAt desc""")
    List<ProductionCard> findInPeriod(Collection<UUID> storeIds, LocalDate from, LocalDate to);

    @Query("""
            select c from ProductionCard c left join fetch c.lines l left join fetch c.createdBy
            left join fetch c.updatedBy
            where c.id = :id""")
    Optional<ProductionCard> findWithLines(UUID id);

    /** Liczba pozycji kart naraz (lista). */
    @Query("""
            select l.card.id, count(l), coalesce(sum(l.quantity), 0) from ProductionCardLine l
            where l.card.id in :cardIds group by l.card.id""")
    List<Object[]> countLines(Collection<UUID> cardIds);
}
