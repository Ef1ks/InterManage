package com.intermanage.backend.sales.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.MissingAssortmentCategory;

public interface MissingAssortmentCategoryRepository extends JpaRepository<MissingAssortmentCategory, UUID> {

    /** Aktywne kategorie sklepów {@code storeIds} (sklep i jego zaprzyjaźnione). */
    @Query("""
            select category from MissingAssortmentCategory category join fetch category.store
            where category.store.id in :storeIds and category.deletedAt is null
            order by lower(category.name)""")
    List<MissingAssortmentCategory> findActive(Collection<UUID> storeIds);

    /** Także usunięta, żeby dało się ją przywrócić. */
    @Query("""
            select category from MissingAssortmentCategory category
            where category.id = :id and category.store.id = :storeId""")
    Optional<MissingAssortmentCategory> findOwned(UUID storeId, UUID id);

    @Query("""
            select count(category) > 0 from MissingAssortmentCategory category
            where category.store.id = :storeId and category.deletedAt is null
            and lower(category.name) = lower(:name) and category.id <> :exceptId""")
    boolean existsActiveName(UUID storeId, String name, UUID exceptId);
}
