package com.intermanage.backend.sales.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.MissingAssortmentAssignment;

public interface MissingAssortmentAssignmentRepository extends JpaRepository<MissingAssortmentAssignment, UUID> {

    /** Obowiązujące przypisania artykułów sklepu (także do kategorii usuniętych albo już niewidocznych). */
    @Query("""
            select assignment from MissingAssortmentAssignment assignment join fetch assignment.category
            where assignment.store.id = :storeId and assignment.removedAt is null""")
    List<MissingAssortmentAssignment> findActive(UUID storeId);

    @Query("""
            select assignment from MissingAssortmentAssignment assignment join fetch assignment.category
            where assignment.store.id = :storeId and assignment.removedAt is null and assignment.itm8 in :itm8s""")
    List<MissingAssortmentAssignment> findActive(UUID storeId, Collection<String> itm8s);
}
