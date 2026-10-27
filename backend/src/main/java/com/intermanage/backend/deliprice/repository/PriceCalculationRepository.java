package com.intermanage.backend.deliprice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliprice.entity.PriceCalculation;

public interface PriceCalculationRepository extends JpaRepository<PriceCalculation, UUID> {

    /** Kalkulacje sklepu z wyrobem, składnikami i ich dostawami, żeby policzyć ceny jednym zapytaniem. */
    @Query("""
            select distinct c from PriceCalculation c
            left join fetch c.product left join fetch c.updatedBy
            left join fetch c.items i left join fetch i.product left join fetch i.priceDocument
            where c.store.id = :storeId and c.deletedAt is null""")
    List<PriceCalculation> findActiveWithItems(UUID storeId);

    @Query("""
            select c from PriceCalculation c left join fetch c.product
            left join fetch c.items i left join fetch i.product left join fetch i.priceDocument
            where c.id = :id and c.store.id = :storeId""")
    Optional<PriceCalculation> findInStore(UUID storeId, UUID id);

    @Query("""
            select count(c) > 0 from PriceCalculation c
            where c.store.id = :storeId and c.product.id = :productId and c.deletedAt is null""")
    boolean existsActiveForProduct(UUID storeId, UUID productId);

    /** Czy wyrób ma już inną kalkulację; {@code exceptId} pusty przy nowej. */
    @Query("""
            select count(c) > 0 from PriceCalculation c
            where c.store.id = :storeId and c.product.id = :productId and c.deletedAt is null
              and (:exceptId is null or c.id <> :exceptId)""")
    boolean existsActiveForProductExcept(UUID storeId, UUID productId, UUID exceptId);
}
